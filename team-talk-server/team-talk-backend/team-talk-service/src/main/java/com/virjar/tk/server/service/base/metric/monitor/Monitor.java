package com.virjar.tk.server.service.base.metric.monitor;

import com.virjar.tk.server.service.base.metric.embed.DefaultMetricSetup;
import com.google.common.util.concurrent.AtomicDouble;
import io.micrometer.core.instrument.*;
import io.micrometer.core.lang.Nullable;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

@Slf4j
public class Monitor {

    static {
        DefaultMetricSetup.setup();
    }

    public static void addRegistry(MeterRegistry registry) {
        Metrics.addRegistry(registry);
    }

    public static void removeRegistry(MeterRegistry registry) {
        Metrics.removeRegistry(registry);
    }

    public static Counter counter(String name, Iterable<Tag> tags) {
        return Metrics.counter(name, tags);
    }

    public static Counter counter(String name, String... tags) {
        if (!checkStringTags(tags)) {
            return Metrics.counter(name);
        }
        return Metrics.counter(name, tags);
    }

    public static DistributionSummary summary(String name, Iterable<Tag> tags) {
        return Metrics.summary(name, tags);
    }

    public static DistributionSummary summary(String name, String... tags) {
        if (!checkStringTags(tags)) {
            return Metrics.summary(name);
        }
        return Metrics.summary(name, tags);
    }

    public static Timer timer(String name, Iterable<Tag> tags) {
        return Metrics.timer(name, tags);
    }

    public static Timer timer(String name, String... tags) {
        if (!checkStringTags(tags)) {
            return Metrics.timer(name);
        }
        return Metrics.timer(name, tags);
    }

    public static Metrics.More more() {
        return Metrics.more();
    }

    @Nullable
    public static <T> T gauge(String name, Iterable<Tag> tags, T obj, ToDoubleFunction<T> valueFunction) {
        return Metrics.gauge(name, tags, obj, valueFunction);
    }

    @Nullable
    public static <T extends Number> T gauge(String name, Iterable<Tag> tags, T number) {
        return Metrics.gauge(name, tags, number);
    }

    private static final Map<Meter.Id, AtomicDouble> fastGaugeMap = new ConcurrentHashMap<>();

    public static AtomicDouble gauge(String name, Iterable<Tag> tags) {
        Meter.Id id = new Meter.Id(name, Tags.of(tags), null, "fast", Meter.Type.GAUGE);
        return fastGaugeMap.computeIfAbsent(id, id1 -> {
            AtomicDouble gauge = new AtomicDouble(0);
            return gauge(name, tags, gauge);
        });
    }


    @Nullable
    public static <T extends Number> T gauge(String name, T number) {
        return Metrics.gauge(name, number);
    }

    @Nullable
    public static <T> T gauge(String name, T obj, ToDoubleFunction<T> valueFunction) {
        return Metrics.gauge(name, obj, valueFunction);
    }

    @Nullable
    public static <T extends Collection<?>> T gaugeCollectionSize(String name, Iterable<Tag> tags, T collection) {
        return Metrics.gaugeCollectionSize(name, tags, collection);
    }

    @Nullable
    public static <T extends Map<?, ?>> T gaugeMapSize(String name, Iterable<Tag> tags, T map) {
        return Metrics.gaugeMapSize(name, tags, map);
    }

    private static boolean checkStringTags(String... tags) {
        if (tags == null) {
            return true;
        }
        if (tags.length % 2 != 0) {
            // 这么做的原因是，对于监控系统，即使是指标错误，理论他的api不应该报错
            // 无论如何他不能干扰业务逻辑
            counter("monitor_tags_num_error").increment();
            log.error("tags num error:{}", String.join(",", tags), new Throwable());
            return false;
        }
        return true;
    }
}
