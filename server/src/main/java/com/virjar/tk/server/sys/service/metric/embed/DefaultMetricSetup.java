package com.virjar.tk.server.sys.service.metric.embed;

import com.virjar.tk.server.sys.service.config.Settings;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;

public class DefaultMetricSetup {
    private static final JvmGcMetrics jvmGcMetrics = new JvmGcMetrics();

    public static void setup() {
        // 系统级别的默认指标，内存/CPU/线程等
        bind(new DiskSpaceMetrics(Settings.Storage.root));
        bind(new JvmThreadMetrics());
        bind(new JvmMemoryMetrics());
        bind(new ClassLoaderMetrics());
        bind(jvmGcMetrics);
        bind(new FileDescriptorMetrics());
        bind(new ProcessorMetrics());
    }

    private static void bind(MeterBinder binder) {
        binder.bindTo(Metrics.globalRegistry);
    }
}
