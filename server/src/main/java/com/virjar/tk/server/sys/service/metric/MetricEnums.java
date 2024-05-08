package com.virjar.tk.server.sys.service.metric;


import java.time.format.DateTimeFormatter;

public class MetricEnums {
    public enum MetricAccuracy {
        minutes(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm")),
        hours(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH")),
        days(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        public final DateTimeFormatter timePattern;

        MetricAccuracy(DateTimeFormatter timePattern) {
            this.timePattern = timePattern;
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
