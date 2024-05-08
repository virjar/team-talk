package com.virjar.tk.server.sys.service.metric.mql.func;

import com.virjar.tk.server.sys.service.metric.MetricEnums;
import com.virjar.tk.server.sys.service.metric.MetricVo;
import com.google.common.collect.Maps;
import io.micrometer.core.instrument.Meter;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * taskEnd[status=true]
 * filter(taskEnd,"status=true")
 */
@MQLFunction.MQL_FUNC("filter")
public class FuncFilter extends XofVarFunc {

    private final Map<String, String> filters = Maps.newHashMap();


    public FuncFilter(List<String> params) {
        super(params);
        int size = params.size();
        if ((size & 0x01) != 1) {
            throw new IllegalStateException("filter function must be");
        }
        for (int i = 1; i < params.size(); i += 2) {
            filters.put(params.get(i), params.get(i + 1));
        }
    }


    @Override
    protected List<MetricVo> apply(List<MetricVo> metricVos) {
        return metricVos.stream()
                .filter(metricVo -> {
                    Map<String, String> tags = metricVo.getTags();
                    return filters.entrySet()
                            .stream()
                            .allMatch(entry ->
                                    StringUtils.equals(tags.get(entry.getKey()), entry.getValue())
                            );
                }).peek(metricVo -> {
                    String metricTimeType = metricVo.getTags().get(MetricEnums.TimeSubType.timer_type);
                    switchMetricType4TimeFilter(metricVo, metricTimeType);

                    Map<String, String> tags = metricVo.getTags();
                    //this field will be removed  after filter
                    filters.keySet().forEach(tags::remove);
                })
                .collect(Collectors.toList());
    }

    public static void switchMetricType4TimeFilter(MetricVo metricVo, String metricTimeType) {
        if (StringUtils.isBlank(metricTimeType)) {
            return;
        }
        if (metricVo.getType() != Meter.Type.TIMER) {
            return;
        }

        // if filter time field, switch metric type
        if (MetricEnums.TimeSubType.TIME.metricKey.equals(metricTimeType) ||
                MetricEnums.TimeSubType.COUNT.metricKey.equals(metricTimeType)
        ) {
            metricVo.setType(Meter.Type.COUNTER);
        }
    }

}
