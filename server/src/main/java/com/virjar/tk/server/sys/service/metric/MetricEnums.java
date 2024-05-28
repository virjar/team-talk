package com.virjar.tk.server.sys.service.metric;


import com.virjar.tk.server.sys.entity.metric.SysMetric;
import com.virjar.tk.server.sys.entity.metric.SysMetricDay;
import com.virjar.tk.server.sys.entity.metric.SysMetricHour;
import com.virjar.tk.server.sys.entity.metric.SysMetricMinute;

import java.time.format.DateTimeFormatter;

public class MetricEnums {
    public enum MetricAccuracy {
        minutes(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm"), SysMetricDay.class),
        hours(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"), SysMetricHour.class),
        days(DateTimeFormatter.ofPattern("yyyy-MM-dd"), SysMetricMinute.class);

        public final DateTimeFormatter timePattern;
        public final Class<? extends SysMetric> handleClazz;

        MetricAccuracy(DateTimeFormatter timePattern, Class<? extends SysMetric> handleClazz) {
            this.timePattern = timePattern;
            this.handleClazz = handleClazz;
        }

    }

    public enum TimeSubType {

        TIME("time"),
        COUNT("count"),
        MAX("max");


        public static final String timer_type = "timer_type";
        public final String metricKey;

        TimeSubType(String metricKey) {
            this.metricKey = metricKey;
        }
    }
}
