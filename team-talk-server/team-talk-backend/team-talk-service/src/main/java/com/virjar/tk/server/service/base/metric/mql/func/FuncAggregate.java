package com.virjar.tk.server.service.base.metric.mql.func;

import com.virjar.tk.server.service.base.metric.MetricEnums;
import com.virjar.tk.server.service.base.metric.MetricVo;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * a aggregate function
 */
@MQLFunction.MQL_FUNC("aggregate")
public class FuncAggregate extends XofVarFunc {

    private final Set<String> aggregateFields = Sets.newHashSet();

    public FuncAggregate(List<String> params) {
        super(params);
        if (params.size() < 2) {
            throw new IllegalStateException("a filter function must have more than 2 params");
        }
        for (int i = 1; i < params.size(); i++) {
            aggregateFields.add(params.get(i));
        }
    }


    @Override
    protected List<MetricVo> apply(List<MetricVo> metricVos) {
        Map<String, List<MetricVo>> group = Maps.newHashMap();

        metricVos.forEach(metricVo -> {
            Map<String, String> tags = metricVo.getTags();
            List<String> keySegment = Lists.newArrayList();
            tags.forEach((tag, value) -> {
                if (!aggregateFields.contains(tag)) {
                    keySegment.add(tag + "#-#" + value);
                }
            });
            Collections.sort(keySegment);
            group.computeIfAbsent(StringUtils.join(keySegment, ","), (s) -> Lists.newArrayList())
                    .add(metricVo);
        });
        return group.values().stream().map(this::doAggregate).collect(Collectors.toList());
    }

    private MetricVo doAggregate(List<MetricVo> metricVos) {
        MetricVo metricVo = MetricVo.cloneMetricVo(metricVos.get(0));
        Map<String, String> tags = metricVo.getTags();
        //this field will be removed  after filter
        aggregateFields.forEach(tags::remove);

        if (metricVos.size() == 1) {
            return metricVo;
        }
        switch (metricVo.getType()) {
            case COUNTER:
            case GAUGE:
                // sum
                metricVo.setValue(metricVos.stream().map(MetricVo::getValue).reduce(0D, Double::sum));
                break;
            case TIMER:
                String timerType = metricVo.getTags().get(MetricEnums.TimeSubType.timer_type);
                if (StringUtils.isBlank(timerType) || timerType.equals(MetricEnums.TimeSubType.MAX.metricKey)) {
                    // this is aggregated time-max
                    metricVo.setValue(metricVos.stream().map(MetricVo::getValue).reduce(0D, Double::max));
                } else if (timerType.equals(MetricEnums.TimeSubType.TIME.metricKey) || timerType.equals(MetricEnums.TimeSubType.COUNT.metricKey)) {
                    metricVo.setValue(metricVos.stream().map(MetricVo::getValue).reduce(0D, Double::sum));
                }
                break;
        }
        return metricVo;
    }
}
