package com.virjar.tk.server.sys.service.metric;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.micrometer.core.instrument.Meter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
public class MetricVo {
    @Schema(name = "指标名称")
    private String name;

    @Schema(name = "时间索引")
    private String timeKey;

    @Schema(name ="精度，分为分钟、小时、天三个维度：minutes/hours/days")
    private MetricEnums.MetricAccuracy accuracy;

    @Schema(name = "分量tag")
    private Map<String, String> tags = Maps.newHashMap();

    @Schema(name = "指标类型：（counter、gauge、timer，请注意暂时只支持这三种指标）")
    private Meter.Type type;

    @Schema(name = "指标值")
    private Double value;

    @Schema(name = "创建时间")
    private LocalDateTime createTime;

    public String toTagId() {
        List<String> tagSegments = Lists.newArrayList();
        tags.forEach((s, s2) -> tagSegments.add(s + "##-##" + s2));
        Collections.sort(tagSegments);
        return StringUtils.join(tagSegments, ",");
    }


    public static MetricVo cloneMetricVo(MetricVo metricVo) {
        MetricVo ret = new MetricVo();
        BeanUtils.copyProperties(metricVo, ret);
        ret.setTags(Maps.newHashMap(metricVo.getTags()));
        return ret;
    }
}
