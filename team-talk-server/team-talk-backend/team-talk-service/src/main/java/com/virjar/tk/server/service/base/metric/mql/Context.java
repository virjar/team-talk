package com.virjar.tk.server.service.base.metric.mql;

import com.virjar.tk.server.service.base.metric.MetricEnums;
import com.virjar.tk.server.service.base.metric.MetricService;
import com.virjar.tk.server.service.base.metric.MetricVo;
import com.google.common.collect.Maps;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Getter
public class Context {
    private final MetricEnums.MetricAccuracy metricAccuracy;

    private final MetricService metricService;

    public Context(MetricEnums.MetricAccuracy metricAccuracy, MetricService metricService) {
        this.metricAccuracy = metricAccuracy;
        this.metricService = metricService;
    }

    private final Map<String, MQLVar> variables = Maps.newHashMap();

    private final Map<String, MQLVar> exportLines = Maps.newHashMap();


    public static class MQLVar {
        @Getter
        public TreeMap<String, List<MetricVo>> data;

        public static MQLVar newVar(TreeMap<String, List<MetricVo>> data) {
            MQLVar mqlVar = new MQLVar();
            mqlVar.data = data;
            return mqlVar;
        }

        public MQLVar copy() {
            TreeMap<String, List<MetricVo>> newData = Maps.newTreeMap();
            data.forEach((s, metricVos) -> newData.put(s, metricVos.stream().map(MetricVo::cloneMetricVo).collect(Collectors.toList())));
            return newVar(newData);
        }
    }
}
