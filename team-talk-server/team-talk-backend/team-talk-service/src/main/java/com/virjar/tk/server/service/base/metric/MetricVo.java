package com.virjar.tk.server.service.base.metric;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.micrometer.core.instrument.Meter;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import proguard.annotation.Keep;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@Keep
public class MetricVo {
    @ApiModelProperty(value = "指标名称")
    private String name;

    @ApiModelProperty(value = "时间索引")
    private String timeKey;

    @ApiModelProperty(value = "精度，分为分钟、小时、天三个维度：minutes/hours/days")
    private MetricEnums.MetricAccuracy accuracy;

    @ApiModelProperty(value = "分量tag")
    private Map<String, String> tags = Maps.newHashMap();

    @ApiModelProperty(value = "指标类型：（counter、gauge、timer，请注意暂时只支持这三种指标）")
    private Meter.Type type;

    @ApiModelProperty(value = "指标值")
    private Double value;

    @ApiModelProperty(value = "创建时间")
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
