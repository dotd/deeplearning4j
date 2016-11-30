package org.deeplearning4j.parallelism;

import com.google.common.primitives.Ints;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import lombok.NonNull;
import org.agrona.BitUtil;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.deeplearning4j.datasets.iterator.AsyncDataSetIterator;
import org.deeplearning4j.datasets.iterator.AsyncMultiDataSetIterator;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.api.Updater;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.updater.graph.ComputationGraphUpdater;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.aeron.ipc.AeronUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.MultiDataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.iterator.MultiDataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.util.ArrayUtil;
import org.nd4j.parameterserver.ParameterServerSubscriber;
import org.nd4j.parameterserver.client.ParameterServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * This is simple data-parallel
 * wrapper suitable for multi-cpu/multi-gpu environments.
 *
 * @author raver119@gmail.com
 */
public class ParallelWrapper implements AutoCloseable {
    private static Logger logger = LoggerFactory.getLogger(ParallelWrapper.class);
    private Model model;
    private int workers;
    private int prefetchSize;
    private int averagingFrequency;
    private Trainer[] zoo;
    private AtomicLong iterationsCounter = new AtomicLong(0);
    private boolean reportScore;
    private boolean averageUpdaters;
    private boolean legacyAveraging;
    private boolean useParameterServer;

    private ParameterServerSubscriber parameterServer;
    private ParameterServerClient parameterServerClient;
    private MediaDriver mediaDriver;
    private Aeron aeron;
    private int parameterServerPort = 40123;
    private int parameterServerStatusPort = 10000;
    private String parameterServerHost = "0.0.0.0";

    protected ParallelWrapper(Model model, int workers, int prefetchSize) {
        this(model,workers,prefetchSize,false);
    }

    protected ParallelWrapper(Model model, int workers, int prefetchSize,boolean useParameterServer) {
        this.model = model;
        this.workers = workers;
        this.prefetchSize = prefetchSize;
        this.useParameterServer = useParameterServer;
        if (this.model instanceof MultiLayerNetwork) {
            ((MultiLayerNetwork) this.model).getUpdater();
        } else if (this.model instanceof ComputationGraph) {
            ((ComputationGraph) this.model).getUpdater();
        }

        zoo = new Trainer[workers];
        for (int cnt = 0; cnt < workers; cnt++) {
            zoo[cnt] = new Trainer(cnt, model);
            zoo[cnt].start();
        }



    }

    private void initParamServer() {
        if(useParameterServer) {
            mediaDriver = MediaDriver.launchEmbedded(AeronUtil.getMediaDriverContext(model.params().length()));
            aeron = Aeron.connect(getContext());
            parameterServer = new ParameterServerSubscriber(mediaDriver);
            parameterServer.setAeron(aeron);
            parameterServer.run(new String[] {
                    "-m","true",
                    "-s","1," + String.valueOf(model.params().length()),
                    "-p",String.valueOf(parameterServerPort),
                    "-h",parameterServerHost,
                    "-id","11",
                    "-md", mediaDriver.aeronDirectoryName(),
                    "-sp", String.valueOf(parameterServerStatusPort)
            });

            parameterServerClient = ParameterServerClient
                    .builder()
                    .aeron(aeron)
                    .ndarrayRetrieveUrl(parameterServer.getResponder().connectionUrl())
                    .ndarraySendUrl(parameterServer.getSubscriber().connectionUrl())
                    .subscriberHost(parameterServerHost)
                    .subscriberPort(40125)
                    .subscriberStream(12).build();


        }
    }

    private Aeron.Context getContext() {
        return new Aeron.Context().publicationConnectionTimeout(-1)
                    .availableImageHandler(AeronUtil::printAvailableImage)
                    .unavailableImageHandler(AeronUtil::printUnavailableImage)
                    .aeronDirectoryName(mediaDriver.aeronDirectoryName())
                    .keepAliveInterval(1000)
                    .errorHandler(e -> logger.error(e.toString(), e));
    }


    @Override
    public void close() throws Exception {
        if (zoo != null) {
            for (int i = 0; i < zoo.length; i++) {
                if (zoo[i] != null)
                    zoo[i].shutdown();
            }
            zoo = null;
        }

        if(mediaDriver != null) {
            mediaDriver.close();
            mediaDriver = null;
        }



    }

    /**
     * This method causes all threads used for parallel training to stop
     */
    public synchronized void shutdown() {
        try {
            close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void fit(@NonNull MultiDataSetIterator source) {
        if (zoo == null) {
            zoo = new Trainer[workers];
            for (int cnt = 0; cnt < workers; cnt++) {
                // we pass true here, to tell Trainer to use MultiDataSet queue for training
                zoo[cnt] = new Trainer(cnt, model, true);
                zoo[cnt].start();
            }
        }
        source.reset();

        MultiDataSetIterator iterator;
        if (prefetchSize > 0 && source.asyncSupported()) {
            iterator = new AsyncMultiDataSetIterator(source, prefetchSize);
        } else iterator = source;

        AtomicInteger locker = new AtomicInteger(0);

        while (iterator.hasNext()) {
            MultiDataSet dataSet = iterator.next();

            /*
             now dataSet should be dispatched to next free workers, until all workers are busy. And then we should block till all finished.
            */
            int pos = locker.getAndIncrement();
            zoo[pos].feedMultiDataSet(dataSet);

            /*
                if all workers are dispatched now, join till all are finished
            */
            if (pos + 1 == workers || !iterator.hasNext()) {
                iterationsCounter.incrementAndGet();

                for (int cnt = 0; cnt < workers && cnt < locker.get(); cnt ++) {
                    try {
                        zoo[cnt].waitTillRunning();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                /*
                    average model, and propagate it to whole
                */
                if (iterationsCounter.get() % averagingFrequency == 0 && pos + 1 == workers) {
                    double score = 0.0;
                    if (!legacyAveraging || Nd4j.getAffinityManager().getNumberOfDevices() == 1) {
                        List<INDArray> params = new ArrayList<>();
                        for (int cnt = 0; cnt < workers && cnt < locker.get(); cnt++) {
                            params.add(zoo[cnt].getModel().params());
                            score += zoo[cnt].getModel().score();
                        }
                        Nd4j.averageAndPropagate(model.params(), params);
                    } else {
                        INDArray params = Nd4j.zeros(model.params().shape());
                        int cnt = 0;
                        for (; cnt < workers && cnt < locker.get(); cnt++) {
                            params.addi(zoo[cnt].getModel().params());
                            score += zoo[cnt].getModel().score();
                        }

                        params.divi(cnt);
                        model.setParams(params);
                    }

                    score /= Math.min(workers, locker.get());

                    // TODO: improve this
                    if (reportScore)
                        logger.info("Averaged score: " + score);

                    // averaging updaters state
                    if (model instanceof ComputationGraph) {
                        if (averageUpdaters) {
                            ComputationGraphUpdater updater = ((ComputationGraph) model).getUpdater();

                            if (updater != null && updater.getStateViewArray() != null) {
                                if (!legacyAveraging || Nd4j.getAffinityManager().getNumberOfDevices() == 1) {
                                    List<INDArray> updaters = new ArrayList<>();
                                    for (int cnt = 0; cnt < workers && cnt < locker.get(); cnt++) {
                                        updaters.add(((ComputationGraph) zoo[cnt].getModel()).getUpdater().getStateViewArray());
                                    }
                                    Nd4j.averageAndPropagate(updater.getStateViewArray(), updaters);
                                } else {
                                    INDArray state = Nd4j.zeros(updater.getStateViewArray().shape());
                                    int cnt = 0;
                                    for (; cnt < workers && cnt < locker.get(); cnt++) {
                                        state.addi(((ComputationGraph) zoo[cnt].getModel()).getUpdater().getStateViewArray());
                                    }
                                    state.divi(cnt);
                                    updater.setStateViewArray(state);
                                }
                            }
                        }

                        ((ComputationGraph) model).setScore(score);
                    } else throw new RuntimeException("MultiDataSet might be used only with ComputationGraph model");

                    if (legacyAveraging &&  Nd4j.getAffinityManager().getNumberOfDevices() > 1) {
                        for (int cnt = 0; cnt < workers; cnt++) {
                            zoo[cnt].updateModel(model);
                        }
                    }
                }
                locker.set(0);
            }
        }

        logger.debug("Iterations passed: {}", iterationsCounter.get());
        iterationsCounter.set(0);
    }

    /**
     * This method takes DataSetIterator, and
     * starts training over it by scheduling
     * DataSets to different executors
     *
     * @param source the source of data to use
     */
    public synchronized void fit(@NonNull DataSetIterator source) {
        if (zoo == null) {
            zoo = new Trainer[workers];
            for (int cnt = 0; cnt < workers; cnt++) {
                zoo[cnt] = new Trainer(cnt, model);
                zoo[cnt].start();
            }
        }
        source.reset();

        if(useParameterServer && parameterServer == null)
            initParamServer();

        DataSetIterator iterator;
        if (prefetchSize > 0 && source.asyncSupported()) {
            iterator = new AsyncDataSetIterator(source, prefetchSize);
        } else iterator = source;

        AtomicInteger locker = new AtomicInteger(0);

        while (iterator.hasNext()) {
            DataSet dataSet = iterator.next();

            /*
             now dataSet should be dispatched to next free workers, until all workers are busy. And then we should block till all finished.
            */
            int pos = locker.getAndIncrement();
            zoo[pos].feedDataSet(dataSet);

            /*
                if all workers are dispatched now, join till all are finished
            */
            if (pos + 1 == workers || !iterator.hasNext()) {
                iterationsCounter.incrementAndGet();

                for (int cnt = 0; cnt < workers && cnt < locker.get(); cnt ++) {
                    try {
                        zoo[cnt].waitTillRunning();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                /*
                    average model, and propagate it to whole
                */
                if (iterationsCounter.get() % averagingFrequency == 0 && pos + 1 == workers) {
                    double score = 0.0;
                    if (!legacyAveraging || Nd4j.getAffinityManager().getNumberOfDevices() == 1) {
                        List<INDArray> params = new ArrayList<>();
                        for (int cnt = 0; cnt < workers && cnt < locker.get(); cnt++) {
                            params.add(zoo[cnt].getModel().params());
                            score += zoo[cnt].getModel().score();
                        }
                        Nd4j.averageAndPropagate(model.params(), params);
                        synchronized (parameterServerClient) {
                            parameterServerClient.pushNDArray(model.params().dup());
                        }
                    }
                    else if(useParameterServer) {
                        parameterServerClient.pushNDArray(model.params().dup());
                    }
                    else {
                        INDArray params = Nd4j.zeros(model.params().shape());
                        int cnt = 0;
                        for (; cnt < workers && cnt < locker.get(); cnt++) {
                            params.addi(zoo[cnt].getModel().params());
                            score += zoo[cnt].getModel().score();
                        }
                        params.divi(cnt);
                        model.setParams(params);
                    }

                    score /= Math.min(workers, locker.get());

                    // TODO: improve this
                    if (reportScore)
                        logger.info("Averaged score: " + score);

                    // averaging updaters state
                    if (model instanceof MultiLayerNetwork) {
                        if (averageUpdaters) {
                            Updater updater = ((MultiLayerNetwork) model).getUpdater();

                            if (updater != null && updater.getStateViewArray() != null) {
                                if (!legacyAveraging || Nd4j.getAffinityManager().getNumberOfDevices() == 1) {
                                    List<INDArray> updaters = new ArrayList<>();
                                    for (int cnt = 0; cnt < workers && cnt < locker.get(); cnt++) {
                                        updaters.add(((MultiLayerNetwork) zoo[cnt].getModel()).getUpdater().getStateViewArray());
                                    }
                                    Nd4j.averageAndPropagate(updater.getStateViewArray(), updaters);
                                } else {
                                    INDArray state = Nd4j.zeros(updater.getStateViewArray().shape());
                                    int cnt = 0;
                                    for (; cnt < workers && cnt < locker.get(); cnt++) {
                                        state.addi(((MultiLayerNetwork) zoo[cnt].getModel()).getUpdater().getStateViewArray().dup());
                                    }
                                    state.divi(cnt);
                                    updater.setStateViewArray((MultiLayerNetwork) model, state, false);
                                }
                            }
                        }

                        ((MultiLayerNetwork) model).setScore(score);
                    } else if (model instanceof ComputationGraph) {
                        if (averageUpdaters) {
                            ComputationGraphUpdater updater = ((ComputationGraph) model).getUpdater();

                            if (updater != null && updater.getStateViewArray() != null) {
                                if (!legacyAveraging || Nd4j.getAffinityManager().getNumberOfDevices() == 1) {
                                    List<INDArray> updaters = new ArrayList<>();
                                    for (int cnt = 0; cnt < workers && cnt < locker.get(); cnt++) {
                                        updaters.add(((ComputationGraph) zoo[cnt].getModel()).getUpdater().getStateViewArray());
                                    }
                                    Nd4j.averageAndPropagate(updater.getStateViewArray(), updaters);
                                } else {
                                    INDArray state = Nd4j.zeros(updater.getStateViewArray().shape());
                                    int cnt = 0;
                                    for (; cnt < workers && cnt < locker.get(); cnt++) {
                                        state.addi(((ComputationGraph) zoo[cnt].getModel()).getUpdater().getStateViewArray());
                                    }
                                    state.divi(cnt);
                                    updater.setStateViewArray(state);
                                }
                            }
                        }

                        ((ComputationGraph) model).setScore(score);
                    }

                    if (legacyAveraging &&  Nd4j.getAffinityManager().getNumberOfDevices() > 1) {
                        for (int cnt = 0; cnt < workers; cnt++) {
                            zoo[cnt].updateModel(model);
                        }
                    }
                }
                locker.set(0);
            }
        }

        logger.debug("Iterations passed: {}", iterationsCounter.get());
        iterationsCounter.set(0);
    }

    public static class Builder {
        private Model model;
        private int workers = 2;
        private int prefetchSize = 16;
        private int averagingFrequency = 1;
        private boolean reportScore = false;
        private boolean averageUpdaters = true;
        private boolean legacyAveraging = true;
        private boolean useParameterServer = false;
        private int parameterServerPort = 40123;
        private int parameterServerStatusPort = 10000;
        private String parameterServerHost = "localhost";

        /**
         * Parameter server host
         * @param parameterServerHost the host to use (default 0.0.0.0)
         * @return
         */
        public Builder parameterServerHost(String parameterServerHost) {
            this.parameterServerHost = parameterServerHost;
            return this;
        }

        /**
         * Parameter server status port for displaying
         * status of parameter server
         * @param parameterServerStatusPort the port to use
         * @return
         */
        public Builder parameterServerStatusPort(int parameterServerStatusPort) {
            this.parameterServerStatusPort = parameterServerStatusPort;
            return this;
        }

        /**
         * Parameter server status port
         * @param parameterServerPort the parameter server status port
         * @return
         */
        public Builder parameterServerPort(int parameterServerPort) {
            this.parameterServerPort = parameterServerPort;
            return this;
        }

        /**
         * Whether to use a parameter server or not
         * @param useParameterServer
         * @return
         */
        public Builder useParameterServer(boolean useParameterServer) {
            this.useParameterServer = useParameterServer;
            return this;
        }

        /**
         * Build ParallelWrapper for MultiLayerNetwork
         *
         * @param mln
         */
        public Builder(@NonNull MultiLayerNetwork mln) {
            model = mln;
        }

        /**
         * Build ParallelWrapper for ComputationGraph
         *
         * @param graph
         */
        public Builder(@NonNull ComputationGraph graph) {
            model = graph;
        }

        /**
         * This method allows to configure number of workers that'll be used for parallel training
         *
         * @param num
         * @return
         */
        public Builder workers(int num) {
            if (num < 2)
                throw new RuntimeException("Number of workers can't be lower then 2!");

            this.workers = num;
            return this;
        }

        /**
         * Model averaging frequency.
         *
         * @param freq number of iterations between averaging
         * @return
         */
        public Builder averagingFrequency(int freq) {
            this.averagingFrequency = freq;
            return this;
        }

        /**
         * This method enables/disables updaters averaging.
         *
         * Default value: TRUE
         *
         * PLEASE NOTE: This method is suitable for debugging purposes mostly. So don't change default value, unless you're sure why you need it.
         *
         * @param reallyAverage
         * @return
         */
        public Builder averageUpdaters(boolean reallyAverage) {
            this.averageUpdaters = reallyAverage;
            return this;
        }


        /**
         * Size of prefetch buffer that will be used for background data prefetching.
         * Usually it's better to keep this value equal to the number of workers.
         *
         * Default value: 2
         *
         * @param size 0 to disable prefetching, any positive number
         * @return
         */
        public Builder prefetchBuffer(int size) {
            if (size < 0)
                size = 0;

            this.prefetchSize = size;

            return this;
        }

        /**
         * If set to true, legacy averaging method is used. This might be used as fallback on multi-gpu systems without P2P access available.
         *
         * Default value: false
         *
         * @param reallyUse
         * @return
         */
        public Builder useLegacyAveraging(boolean reallyUse) {
            this.legacyAveraging = reallyUse;
            return this;
        }


        /**
         * This method enables/disables averaged model score reporting
         *
         * @param reallyReport
         * @return
         */
        public Builder reportScoreAfterAveraging(boolean reallyReport) {
            this.reportScore = reallyReport;
            return this;
        }

        /**
         * This method returns ParallelWrapper instance
         *
         * @return
         */
        public ParallelWrapper build() {
            ParallelWrapper wrapper = new ParallelWrapper(model, workers, prefetchSize,useParameterServer);
            wrapper.averagingFrequency = this.averagingFrequency;
            wrapper.reportScore = this.reportScore;
            wrapper.averageUpdaters = this.averageUpdaters;
            wrapper.legacyAveraging = this.legacyAveraging;
            wrapper.useParameterServer = useParameterServer;
            wrapper.parameterServerPort = parameterServerPort;
            wrapper.parameterServerStatusPort = parameterServerStatusPort;
            wrapper.parameterServerHost = parameterServerHost;
            return wrapper;
        }
    }

    private static class Trainer extends Thread implements Runnable {
        private Model originalModel;
        private Model replicatedModel;
        private LinkedBlockingQueue<DataSet> queue = new LinkedBlockingQueue<>();
        private LinkedBlockingQueue<MultiDataSet> queueMDS = new LinkedBlockingQueue<>();
        private AtomicInteger running = new AtomicInteger(0);
        private int threadId;
        private AtomicBoolean shouldUpdate = new AtomicBoolean(false);
        private AtomicBoolean shouldStop = new AtomicBoolean(false);
        private Exception thrownException;
        private boolean useMDS = false;
        private boolean useParameterServer = false;
        private ParameterServerClient parameterServerClient;

        public Trainer(int threadId, Model model, boolean useMDS,boolean useParameterServer) {
            this(threadId, model);
            this.useMDS = useMDS;
            this.useParameterServer = useParameterServer;
        }

        public Trainer(int threadId, Model model, boolean useMDS) {
            this(threadId,model,useMDS,false);
        }

        public Trainer(int threadId, Model model) {
            this.threadId = threadId;
            this.setDaemon(true);
            this.setName("ParallelWrapper trainer " + threadId);

            this.originalModel = model;
            if (model instanceof MultiLayerNetwork) {
                MultiLayerNetwork cloned = (MultiLayerNetwork) model;
                this.replicatedModel = cloned.clone();
                if (threadId != 0)
                    ((MultiLayerNetwork)this.replicatedModel).setListeners(new ArrayList<>());
            } else if (model instanceof ComputationGraph) {
                this.replicatedModel = ((ComputationGraph) model).clone();

                if (threadId != 0)
                    ((ComputationGraph)this.replicatedModel).setListeners(new ArrayList<>());
            }
        }

        public void feedMultiDataSet(@NonNull MultiDataSet dataSet) {
            running.incrementAndGet();
            queueMDS.add(dataSet);
        }

        public void feedDataSet(@NonNull DataSet dataSet) {
            running.incrementAndGet();
            queue.add(dataSet);
        }

        public Model getModel() {
            return replicatedModel;
        }

        public void updateModel(@NonNull Model model) {
            this.shouldUpdate.set(true);

            if (replicatedModel instanceof MultiLayerNetwork) {
                replicatedModel.setParams(model.params().dup());

                Updater updater = ((MultiLayerNetwork) model).getUpdater();
                INDArray view = updater.getStateViewArray();

                if (view != null) {
                    updater = ((MultiLayerNetwork) replicatedModel).getUpdater();
                    INDArray viewD = view.dup();

                    if (Nd4j.getExecutioner() instanceof GridExecutioner)
                        ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

                    updater.setStateViewArray((MultiLayerNetwork) replicatedModel, viewD, false);
                }
            } else if (replicatedModel instanceof  ComputationGraph) {
                replicatedModel.setParams(model.params().dup());

                ComputationGraphUpdater updater = ((ComputationGraph) model).getUpdater();
                INDArray view = updater.getStateViewArray();

                if (view != null) {
                    INDArray viewD = view.dup();

                    if (Nd4j.getExecutioner() instanceof GridExecutioner)
                        ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

                    updater = ((ComputationGraph) replicatedModel).getUpdater();
                    updater.setStateViewArray(viewD);
                }
            }

            if (Nd4j.getExecutioner() instanceof GridExecutioner)
                ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();
        }

        public boolean isRunning() {
            // if Trainer thread got exception during training - rethrow it here
            if (thrownException != null)
                throw new RuntimeException(thrownException);

            return running.get() == 0;
        }

        public void shutdown() {
            shouldStop.set(true);
        }

        @Override
        public void run() {
            try {
                // we create fresh network, with the same configuration, as initially created by user
                // however, we don't need clone or anything here
                if (originalModel instanceof MultiLayerNetwork) {
                    MultiLayerConfiguration conf = ((MultiLayerNetwork) originalModel).getLayerWiseConfigurations().clone();
                    this.replicatedModel = new MultiLayerNetwork(conf);

                    ((MultiLayerNetwork) replicatedModel).init();
                } else if (originalModel instanceof ComputationGraph) {
                    this.replicatedModel = new ComputationGraph(((ComputationGraph) originalModel).getConfiguration().clone());

                    ((ComputationGraph) this.replicatedModel).init();
                }

                if (!useMDS) {
                    while (!shouldStop.get()) {
                        DataSet dataSet = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (dataSet != null) {
                            if (replicatedModel instanceof MultiLayerNetwork) {
                                ((MultiLayerNetwork) replicatedModel).fit(dataSet);
                            } else if (replicatedModel instanceof ComputationGraph) {
                                ((ComputationGraph) replicatedModel).fit(dataSet);
                            }

                            if (Nd4j.getExecutioner() instanceof GridExecutioner)
                                ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

                            running.decrementAndGet();
                        }
                    }
                } else {
                    // loop for MultiDataSet
                    MultiDataSet dataSet = queueMDS.poll(100, TimeUnit.MILLISECONDS);
                    if (dataSet != null) {
                        if (replicatedModel instanceof ComputationGraph) {
                            ((ComputationGraph) replicatedModel).fit(dataSet);
                        } else throw new RuntimeException("MultiDataSet can be fit into ComputationGraph only");

                        if (Nd4j.getExecutioner() instanceof GridExecutioner)
                            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();

                        running.decrementAndGet();
                    }
                }
            } catch (Exception e) {
                this.thrownException = e;
            }
        }

        public void waitTillRunning() {
            while (isRunning()) {

                // if Trainer thread got exception during training - rethrow it here
                if (thrownException != null)
                    throw new RuntimeException(thrownException);

                LockSupport.parkNanos(1000);
            }
        }
    }
}
