package com.virjar.tk.server.sys.service.metric;

import com.virjar.tk.server.sys.service.metric.mql.Context;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * show mql query data as EChart Option
 */
@Data
public class EChart4MQL {
    /**
     * 共有多少条线
     */
    private List<String> legends = Lists.newArrayList();

    /**
     * x坐标，每当有一个时间点（time），则存在一个值，里面是时间点集合
     */
    private List<String> xAxis = Lists.newArrayList();


    /**
     * 具体的数据，每个子List代表一条线，List的顺序和数量和legends对其
     */
    private List<Serial> series = Lists.newArrayList();

    @Data
    public static class Serial {
        private String name;
        private String type = "line";
        private List<Double> data = Lists.newArrayList();
    }

    public static String legendId(String varName, MetricVo metricVo, boolean singleVar) {
        List<String> tagDisplayIds = Lists.newArrayList();
        Map<String, String> tags = metricVo.getTags();
        if (tags.size() == 1) {
            tagDisplayIds.add(tags.values().iterator().next());
        } else {
            tags.forEach((tag, value) -> tagDisplayIds.add(tag + "#" + value));
            Collections.sort(tagDisplayIds);
        }
        if (!singleVar) {
            tagDisplayIds.add(0, varName);
        }
        if (tagDisplayIds.isEmpty()) {
            return varName;
        }
        return StringUtils.join(tagDisplayIds, "-");
    }

    public static EChart4MQL fromMQLResult(Map<String, Context.MQLVar> exportData) {
        EChart4MQL eChart4MQL = new EChart4MQL();

        Set<String> legendSet = Sets.newTreeSet();

        // 遍历所有的数据，计算有多少条折线，这是因为每个指标都可能有tag（即子维度，那么线条渲染需要拆分为多个子维度）
        //------ time------- metric----- value
        TreeMap<String, Map<String, List<MetricVo>>> metricGroupByTime = Maps.newTreeMap();
        boolean singleVar = exportData.size() == 1;
        exportData.forEach((varName, mqlVar) -> mqlVar.getData().forEach((timeStr, metricVos) -> {
                    Map<String, List<MetricVo>> mapTime = metricGroupByTime.computeIfAbsent(timeStr, s -> Maps.newHashMap());
                    mapTime.put(varName, metricVos);

                    if (metricVos.isEmpty()) {
                        // 指标为空，代表整个指标都被过滤了,此时保护指标，后续流程会填充0值
                        legendSet.add(varName);
                    } else {
                        metricVos.forEach(metricVo -> {// many sub tag data for an xAxis
                            legendSet.add(legendId(varName, metricVo, singleVar));
                        });
                    }
                })
        );

        // 数据容器初始化
        List<String> legends = eChart4MQL.legends;
        legends.addAll(legendSet);
        List<Serial> series = eChart4MQL.series;
        Map<String, Serial> serialRef = Maps.newHashMap();
        legends.forEach(legend -> {
            Serial serial = new Serial();
            serial.name = legend;
            series.add(serial);
            serialRef.put(legend, serial);
        });

        // 填充数据
        List<String> xAxis = eChart4MQL.xAxis;
        metricGroupByTime.forEach((time, timeData) -> {
            // 一个X坐标
            xAxis.add(time);
            // 当前X坐标下，对应的Y值
            Set<Serial> points = Sets.newHashSet(series);

            timeData.forEach((varName, metricVos) -> metricVos.forEach(metricVo -> {
                String legend = legendId(varName, metricVo, singleVar);
                Serial line = serialRef.get(legend);
                points.remove(line);
                line.data.add(metricVo.getValue());
            }));

            // 当前X下，没有对应Y值，则设置为0
            points.forEach(doubles -> doubles.data.add(0D));
        });
        return eChart4MQL;
    }
}
