package com.virjar.tk.server.controller;

import com.virjar.tk.server.TeamTalkMain;
import com.virjar.tk.server.entity.CommonRes;
import com.virjar.tk.server.entity.metric.Metric;
import com.virjar.tk.server.entity.metric.MetricDay;
import com.virjar.tk.server.entity.metric.MetricTag;
import com.virjar.tk.server.mapper.metric.MetricTagMapper;
import com.virjar.tk.server.service.base.BroadcastService;
import com.virjar.tk.server.service.base.env.Constants;
import com.virjar.tk.server.service.base.metric.*;
import com.virjar.tk.server.service.base.metric.mql.MQL;
import com.virjar.tk.server.system.LoginRequired;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

@RestController
@Api("监控指标相关")
@RequestMapping(Constants.RESTFULL_API_PREFIX + "/metric")
@Validated
@Slf4j
public class MetricController {

    @Resource
    private MetricService metricService;

    @Resource
    private MetricTagService metricTagService;


    @Resource
    private MetricTagMapper metricTagMapper;

    @Data
    public static class MetricQueryRequest {
        @NotBlank
        private String name;
        private Map<String, String> query;
        @NotNull
        private MetricEnums.MetricAccuracy accuracy;
    }

    @ApiOperation("查询指标,可以指定tag")
    @PostMapping("/queryMetric")
    @LoginRequired
    public CommonRes<List<MetricVo>> queryMetric(@RequestBody @Validated MetricQueryRequest metricQueryRequest) {
        return CommonRes.success(metricService.queryMetric(metricQueryRequest.name, metricQueryRequest.query, metricQueryRequest.accuracy));
    }

    @ApiOperation("查询指标,使用mql语言查询")
    @RequestMapping(value = "/mqlQuery", method = {RequestMethod.GET, RequestMethod.POST})
    @LoginRequired
    public CommonRes<EChart4MQL> mqlQuery(@NotBlank String mqlScript, @NotNull MetricEnums.MetricAccuracy accuracy) {
        return CommonRes.call(() -> EChart4MQL.fromMQLResult(MQL.compile(mqlScript).run(accuracy, metricService)));
    }

    @ApiOperation("指标列表")
    @GetMapping("/metricNames")
    @LoginRequired
    public CommonRes<List<String>> metricNames() {
        return CommonRes.success(metricTagService.metricNames());
    }


    @ApiOperation("指标Tag详情")
    @GetMapping("/metricTag")
    @LoginRequired
    public CommonRes<MetricTag> queryMetricTag(@NotBlank String metricName) {
        return CommonRes.ofPresent(metricTagService.fromKey(metricName));
    }

    @ApiOperation("所有的指标详情")
    @GetMapping("/allMetricConfig")
    @LoginRequired
    public CommonRes<List<MetricTag>> allMetricConfig() {
        return CommonRes.success(metricTagService.tagList());
    }

    @ApiOperation("删除一个指标")
    @GetMapping("/deleteMetric")
    @LoginRequired
    public CommonRes<String> deleteMetric(@NotBlank String metricName) {
        TeamTalkMain.getShardThread().post(() -> {
            metricService.eachDao(mapper -> mapper.delete(new QueryWrapper<Metric>().eq(MetricDay.NAME, metricName)));
            metricTagMapper.delete(new QueryWrapper<MetricTag>().eq(MetricTag.NAME, metricName));
            BroadcastService.triggerEvent(BroadcastService.Topic.METRIC_TAG);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            metricService.eachDao(mapper -> mapper.delete(new QueryWrapper<Metric>().eq(MetricDay.NAME, metricName)));
        });
        return CommonRes.success("ok");
    }

}
