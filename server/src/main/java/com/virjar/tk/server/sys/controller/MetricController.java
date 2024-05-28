package com.virjar.tk.server.sys.controller;

import com.virjar.tk.server.TeamTalkMain;
import com.virjar.tk.server.common.CommonRes;
import com.virjar.tk.server.sys.LoginRequired;
import com.virjar.tk.server.sys.entity.metric.SysMetricTag;
import com.virjar.tk.server.sys.mapper.metric.SysMetricTagMapper;
import com.virjar.tk.server.sys.service.BroadcastService;
import com.virjar.tk.server.sys.service.env.Constants;
import com.virjar.tk.server.sys.service.metric.*;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
        Flux<MetricVo> flux = metricService.queryMetric(
                metricQueryRequest.name, metricQueryRequest.query, metricQueryRequest.accuracy
        );
        return CommonRes.fromMono(flux.collectList());
    }

    @Operation(summary = "查询指标,使用mql语言查询")
    @RequestMapping(value = "/mqlQuery", method = {RequestMethod.GET, RequestMethod.POST})
    @LoginRequired
    public Mono<CommonRes<EChart4MQL>> mqlQuery(@NotBlank String mqlScript, @NotNull MetricEnums.MetricAccuracy accuracy) {
        // todo mql指标运算没有实现异步化，并且在短期不容易实现
        // todo 我们需要专门开一个线程池给这些暂时不方便异步化的模块使用
        return Mono.create(commonResMonoSink -> TeamTalkMain.getShardThread().execute(() -> {
            CommonRes<EChart4MQL> commonRes = CommonRes.call(() ->
                    EChart4MQL.fromMQLResult(MQL.compile(mqlScript)
                            .run(accuracy, metricService))
            );
            commonResMonoSink.success(commonRes);
        }));
    }

    @Operation(summary = "指标列表")
    @GetMapping("/metricNames")
    @LoginRequired
    public CommonRes<List<String>> metricNames() {
        return CommonRes.success(metricTagService.metricNames());
    }


    @Operation(summary = "指标Tag详情")
    @GetMapping("/metricTag")
    @LoginRequired
    public Mono<CommonRes<SysMetricTag>> queryMetricTag(@NotBlank String metricName) {
        return CommonRes.fromMono(metricTagService.fromKey(metricName));
    }

    @Operation(summary = "所有的指标详情")
    @GetMapping("/allMetricConfig")
    @LoginRequired
    public CommonRes<List<SysMetricTag>> allMetricConfig() {
        return CommonRes.success(metricTagService.tagList());
    }

    @Operation(summary = "删除一个指标")
    @GetMapping("/deleteMetric")
    @LoginRequired
    public Mono<CommonRes<Long>> deleteMetric(@NotBlank String metricName) {
        Mono<Long> longMono = metricTagMapper.deleteByName(metricName)
                .flatMap((Function<Long, Mono<Long>>) unused ->
                        metricService.eachDao(mapper -> mapper.deleteByName(metricName))
                                .doOnComplete(() ->
                                        BroadcastService.triggerEvent(BroadcastService.Topic.METRIC_TAG))
                                .count()
                                .delayElement(Duration.ofSeconds(2))
                                .flatMap((Function<Long, Mono<Long>>) aLong ->
                                        metricService.eachDao(mapper -> mapper.deleteByName(metricName)).count())
                );
        return CommonRes.fromMono(longMono);
    }

}
