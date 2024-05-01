package com.virjar.tk.server.service.base.metric.embed;

import com.virjar.tk.server.service.base.metric.monitor.Monitor;
import com.virjar.tk.server.service.base.config.Configs;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import javax.validation.constraints.NotNull;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * a framework custom threadPool
 * <ul>
 *     <li>will be monitor by metric component</li>
 *     <li>can be reset thread size by cloud config</li>
 * </ul>
 */
public class MonitorThreadPoolExecutor extends ThreadPoolExecutor {
    private static final String METRIC_NAME_GAUGE = "thread.pool.gauge";
    private static final String METRIC_NAME_COUNT = "thread.pool.count";
    private static final String METRIC_NAME_TIMER = "thread.pool.timer";
    private final String name;
    private Timer timeMonitor;
    private Counter exceptionCounter;

    public MonitorThreadPoolExecutor(int corePoolSize,
                                     int maximumPoolSize,
                                     String name,
                                     long keepAliveTime,
                                     TimeUnit unit,
                                     BlockingQueue<Runnable> workQueue,
                                     RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize,
                keepAliveTime, unit, workQueue,
                new NamedThreadFactory(name),
                new MonitorRejectedExecutionHandler(handler, name)
        );
        this.name = name;
        // all metric monitor of this threadPool
        setupMonitor();
    }

    public MonitorThreadPoolExecutor(Configs.IntegerConfigValue threadSizeConfig,
                                     String name,
                                     BlockingQueue<Runnable> workQueue) {
        this(threadSizeConfig, name, workQueue, new AbortPolicy());
    }

    public MonitorThreadPoolExecutor(Configs.IntegerConfigValue threadSizeConfig,
                                     String name,
                                     BlockingQueue<Runnable> workQueue,
                                     RejectedExecutionHandler handler) {
        super(threadSizeConfig.value, threadSizeConfig.value,
                0, TimeUnit.MINUTES, workQueue,
                new NamedThreadFactory(name),
                new MonitorRejectedExecutionHandler(handler, name)
        );
        this.name = name;
        // all metric monitor of this threadPool
        setupMonitor();

        // re config thread size from configs
        linkThreadSizeConfig(threadSizeConfig);
    }


    @Override
    public void execute(@NotNull Runnable command) {
        super.execute(timeMonitor.wrap(command));
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        super.afterExecute(r, t);
        if (t != null) {
            exceptionCounter.increment();
        }
    }

    private void linkThreadSizeConfig(Configs.IntegerConfigValue threadSizeConfig) {
        Configs.addKeyMonitor(threadSizeConfig.key, () -> {
            Integer newThreadSize = threadSizeConfig.value;
            int corePoolSize = getCorePoolSize();
            if (newThreadSize < corePoolSize) {
                setCorePoolSize(newThreadSize);
                setMaximumPoolSize(newThreadSize);
            } else if (newThreadSize > corePoolSize) {
                setMaximumPoolSize(newThreadSize);
                setCorePoolSize(newThreadSize);
            }
        });
    }

    private void setupMonitor() {
        registerGauge("coreSize", it -> (double) it.getCorePoolSize());
        registerGauge("activeCount", it -> (double) it.getActiveCount());
        registerGauge("queueSize", it -> (double) it.getQueue().size());
        timeMonitor = Monitor.timer(METRIC_NAME_TIMER, "name", name);
        exceptionCounter = Monitor.counter(METRIC_NAME_COUNT, "name", name, "type", "exception");
    }

    private void registerGauge(String type, Function<MonitorThreadPoolExecutor, Double> function) {
        Monitor.gauge(METRIC_NAME_GAUGE, Tags.of("name", name, "type", type), this, function::apply);
    }

    private static class MonitorRejectedExecutionHandler implements RejectedExecutionHandler {
        private final RejectedExecutionHandler delegate;
        private final Counter rejectCounter;

        public MonitorRejectedExecutionHandler(RejectedExecutionHandler delegate, String threadName) {
            this.delegate = delegate;
            this.rejectCounter = Monitor.counter(METRIC_NAME_COUNT, "name", threadName, "type", "reject");
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            rejectCounter.increment();
            delegate.rejectedExecution(r, executor);
        }
    }


    public static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger(1);
        private final String prefix;

        public NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            int seq = sequence.getAndIncrement();
            thread.setName(prefix + (seq > 1 ? "-" + seq : ""));
            if (!thread.isDaemon())
                thread.setDaemon(true);
            return thread;
        }
    }
}
