package com.virjar.tk.server.sys.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.virjar.tk.server.TeamTalkMain;
import com.virjar.tk.server.sys.LoginRequired;
import com.virjar.tk.server.sys.service.metric.*;
import com.virjar.tk.server.common.CommonRes;
import com.virjar.tk.server.sys.entity.metric.SysMetric;
import com.virjar.tk.server.sys.entity.metric.SysMetricDay;
import com.virjar.tk.server.sys.entity.metric.SysMetricTag;
import com.virjar.tk.server.sys.mapper.metric.SysMetricTagMapper;
import com.virjar.tk.server.sys.service.BroadcastService;
import com.virjar.tk.server.sys.service.env.Constants;
import com.virjar.tk.server.sys.service.metric.mql.MQL;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "监控指标相关")
@RequestMapping(Constants.RESTFULL_API_PREFIX + "/metric")
@Validated
@Slf4j
public class MetricController {

    @Resource
    private MetricService metricService;

    @Resource
    private MetricTagService metricTagService;


    @Resource
    private SysMetricTagMapper metricTagMapper;

    @Data
    public static class MetricQueryRequest {
        @NotBlank
        private String name;
        private Map<String, String> query;
        @NotNull
        private MetricEnums.MetricAccuracy accuracy;
    }

    @Operation(summary = "查询指标,可以指定tag")
    @PostMapping("/queryMetric")
    @LoginRequired
    public Mono<CommonRes<List<MetricVo>>> queryMetric(@RequestBody @Validated MetricQueryRequest metricQueryRequest) {
        return CommonRes.success(metricService.queryMetric(metricQueryRequest.name, metricQueryRequest.query, metricQueryRequest.accuracy));
    }

    @Operation(summary = "查询指标,使用mql语言查询")
    @RequestMapping(value = "/mqlQuery", method = {RequestMethod.GET, RequestMethod.POST})
    @LoginRequired
    public Mono<CommonRes<EChart4MQL>> mqlQuery(@NotBlank String mqlScript, @NotNull MetricEnums.MetricAccuracy accuracy) {
        return CommonRes.call(() -> EChart4MQL.fromMQLResult(MQL.compile(mqlScript).run(accuracy, metricService)));
    }

    @Operation(summary = "指标列表")
    @GetMapping("/metricNames")
    @LoginRequired
    public Mono<CommonRes<List<String>>> metricNames() {
        return CommonRes.success(metricTagService.metricNames());
    }


    @Operation(summary = "指标Tag详情")
    @GetMapping("/metricTag")
    @LoginRequired
    public Mono<CommonRes<SysMetricTag>> queryMetricTag(@NotBlank String metricName) {
        return CommonRes.ofPresent(metricTagService.fromKey(metricName));
    }

    @Operation(summary = "所有的指标详情")
    @GetMapping("/allMetricConfig")
    @LoginRequired
    public Mono<CommonRes<List<SysMetricTag>>> allMetricConfig() {
        return CommonRes.success(metricTagService.tagList());
    }

    @Operation(summary = "删除一个指标")
    @GetMapping("/deleteMetric")
    @LoginRequired
    public Mono<CommonRes<String>> deleteMetric(@NotBlank String metricName) {
        TeamTalkMain.getShardThread().post(() -> {
            metricService.eachDao(mapper -> mapper.delete(new QueryWrapper<SysMetric>().eq(SysMetricDay.NAME, metricName)));
            metricTagMapper.delete(new QueryWrapper<SysMetricTag>().eq(SysMetricTag.NAME, metricName));
            BroadcastService.triggerEvent(BroadcastService.Topic.METRIC_TAG);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            metricService.eachDao(mapper -> mapper.delete(new QueryWrapper<SysMetric>().eq(SysMetricDay.NAME, metricName)));
        });
        return CommonRes.success("ok");
    }

}
