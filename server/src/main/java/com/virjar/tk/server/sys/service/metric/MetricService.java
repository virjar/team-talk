package com.virjar.tk.server.sys.service.metric;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.virjar.tk.server.sys.entity.metric.*;
import com.virjar.tk.server.sys.mapper.metric.SysMetricBaseMapper;
import com.virjar.tk.server.sys.mapper.metric.SysMetricDayMapper;
import com.virjar.tk.server.sys.mapper.metric.SysMetricHourMapper;
import com.virjar.tk.server.sys.mapper.metric.SysMetricMinuteMapper;
import com.virjar.tk.server.sys.service.env.Environment;
import com.virjar.tk.server.sys.service.safethread.Looper;
import com.virjar.tk.server.utils.ReflectUtil;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MetricService {
    private final Looper metricHandleLooper = new Looper("metric_handler").startLoop();

    @Resource
    private SysMetricDayMapper metricDayMapper;

    @Resource
    private SysMetricHourMapper metricHourMapper;

    @Resource
    private SysMetricMinuteMapper metricMinuteMapper;

    @Resource
    private MetricTagService metricTagService;

    @Resource
    private R2dbcEntityTemplate r2dbcEntityTemplate;

    @Resource
    private DatabaseClient databaseClient;

    @Resource
    private RelationalMappingContext relationalMappingContext;
    @Autowired
    private SysMetricMinuteMapper sysMetricMinuteMapper;

    @PostConstruct
    public void registerShutdownHook() {
        Environment.registerShutdownHook(this::publishMetrics);
    }

    @Scheduled(cron = "5 * * * * ?")
    public void publishMetrics() {
        if (Environment.isLocalDebug) {
            return;
        }
        log.info("schedule generator metric data for timeKey: {}",
                LocalDateTime.now().format(MetricEnums.MetricAccuracy.minutes.timePattern)
        );
        LocalDateTime now = LocalDateTime.now();
        String timeKey = now.format(MetricEnums.MetricAccuracy.minutes.timePattern);
        Metrics.globalRegistry.forEachMeter(meter -> {
            try {
                onMeter(timeKey, now, meter);
            } catch (Exception e) {
                log.error("error", e);
            }
        });
    }

    private final MergeScanStartCtr minuteCrt = new MergeScanStartCtr(
            8 * 60, 2 * 60
    );

    @Scheduled(cron = "15 0/20 * * * ?")
    public void scheduleMergeMinuteToHours() {
        if (Environment.isLocalDebug) {
            return;
        }
        // todo 定时任务没有异步化，后面在看怎么处理
        log.info("schedule mergeHoursMetric");
        metricHandleLooper.execute(() ->
                performMerge(
                        MetricEnums.MetricAccuracy.minutes, MetricEnums.MetricAccuracy.hours,
                        LocalDateTime.now().minusHours(36), minuteCrt.scanStart(),
                        localDateTime -> localDateTime.plusHours(1),
                        localDateTime -> localDateTime.withMinute(0).withSecond(0)
                ));
    }

    private final MergeScanStartCtr hourCrt = new MergeScanStartCtr(
            8 * 24 * 60, 2 * 24 * 60
    );

    @Scheduled(cron = "40 10 0/5 * * ?")
    public void scheduleMergeHourToDays() {
        if (Environment.isLocalDebug) {
            return;
        }
        log.info("schedule mergeHourToDays");
        metricHandleLooper.execute(() ->
                performMerge(
                        MetricEnums.MetricAccuracy.hours, MetricEnums.MetricAccuracy.days,
                        LocalDateTime.now().minusDays(40), hourCrt.scanStart(),
                        localDateTime -> localDateTime.plusDays(1),
                        localDateTime -> localDateTime.withHour(0).withMinute(0).withSecond(0)
                ));
    }

    @Scheduled(cron = "0 4 4 14 * ?")
    public void scheduleCleanDays() {
        Criteria criteria = Criteria.empty().and(SysMetric.CREATE_TIME).lessThanOrEquals(
                LocalDateTime.now().minusDays(1000)
        );
        // 最多保留3年的指标数据，超过3年的直接删除
        r2dbcEntityTemplate.delete(Query.query(criteria), SysMetricDay.class)
                .subscribe();
    }

    /**
     * 获取指标数据，根据key查询
     *
     * @param name     指标名称
     * @param accuracy 精度，包括分钟、小时、天，三个维度
     * @return 指标集合，请注意这里不会对数据做过滤。同时因为我们对指标有提前聚合，所以返回结果集是可控的，大约在千以内
     */
    public Flux<MetricVo> queryMetric(String name, Map<String, String> query, MetricEnums.MetricAccuracy accuracy) {
        return metricTagService.fromKey(name)
                .flatMapMany((tag) -> {
                    Criteria criteria = Criteria.empty()
                            .and(SysMetricDay.NAME).is(name);
                    criteria = metricTagService.wrapQueryWithTags(criteria, query, tag);
                    Query queryWrapper = Query.query(criteria).sort(Sort.by(SysMetric.TIME_KEY));

                    return r2dbcEntityTemplate.select(queryWrapper, accuracy.handleClazz)
                            .map((Function<SysMetric, MetricVo>) sysMetric -> mapMetric(sysMetric, tag))
                            .doOnNext(metricVo -> {
                                // remove tag field after
                                Map<String, String> tags = metricVo.getTags();
                                query.keySet().forEach(tags::remove);
                            });


                });
    }


    private MetricVo mapMetric(SysMetric metric, SysMetricTag metricTag) {
        MetricVo metricVo = new MetricVo();
        Map<String, String> tags = metricVo.getTags();
        BeanUtils.copyProperties(metric, metricVo);
        if (StringUtils.isNotBlank(metricTag.getTag1Name())) {
            tags.put(metricTag.getTag1Name(), metric.getTag1());
        }
        if (StringUtils.isNotBlank(metricTag.getTag2Name())) {
            tags.put(metricTag.getTag2Name(), metric.getTag2());
        }
        if (StringUtils.isNotBlank(metricTag.getTag3Name())) {
            tags.put(metricTag.getTag3Name(), metric.getTag3());
        }
        if (StringUtils.isNotBlank(metricTag.getTag4Name())) {
            tags.put(metricTag.getTag4Name(), metric.getTag4());
        }
        if (StringUtils.isNotBlank(metricTag.getTag5Name())) {
            tags.put(metricTag.getTag5Name(), metric.getTag5());
        }
        return metricVo;
    }


    private void performMerge(MetricEnums.MetricAccuracy fromAccuracy,
                              MetricEnums.MetricAccuracy toAccuracy,
                              LocalDateTime cleanBefore, LocalDateTime scanStart,
                              Function<LocalDateTime, LocalDateTime> stepFun,
                              Function<LocalDateTime, LocalDateTime> stepStartFun) {
        metricTagService.metricNames()
                .forEach(metricName ->
                        mergeOneMetric(metricName, fromAccuracy, toAccuracy, cleanBefore, scanStart, stepFun, stepStartFun)
                );
    }

    private Mono<LocalDateTime> findScanStart(LocalDateTime input, String metricName, MetricEnums.MetricAccuracy fromAccuracy) {
        if (input != null) {
            return Mono.just(input);
        }
        Query query = Query.query(Criteria.where(SysMetric.NAME).is(metricName))
                .sort(Sort.by(SysMetric.TIME_KEY))
                .limit(1);
        return r2dbcEntityTemplate.selectOne(query, fromAccuracy.handleClazz)
                .map((Function<SysMetric, LocalDateTime>) SysMetric::getCreateTime);
    }

    private void mergeOneMetric(String metricName, MetricEnums.MetricAccuracy fromAccuracy,
                                MetricEnums.MetricAccuracy toAccuracy,
                                LocalDateTime cleanBefore, LocalDateTime scanStart,
                                Function<LocalDateTime, LocalDateTime> stepFun,
                                Function<LocalDateTime, LocalDateTime> stepStartFun) {
        findScanStart(scanStart, metricName, fromAccuracy)
                .doOnSuccess(scanStart_ -> {
                    LocalDateTime now = LocalDateTime.now();
                    while (scanStart_.isBefore(now)) {
                        LocalDateTime start = stepStartFun.apply(scanStart_);
                        LocalDateTime end = stepFun.apply(start);
                        mergeTimesSpace(start, end, fromAccuracy, toAccuracy, metricName, cleanBefore);
                        scanStart_ = end.plusMinutes(30);
                    }
                }).flatMap((Function<LocalDateTime, Mono<Long>>) localDateTime -> {
                    // clean metric data which has been merged and produced for a long time
                    return r2dbcEntityTemplate.delete(Query.query(
                            Criteria.where(SysMetric.NAME).is(metricName)
                                    .and(SysMetric.CREATE_TIME).lessThan(cleanBefore)
                    ), fromAccuracy.handleClazz);
                })
                .subscribe();
    }

    private void mergeTimesSpace(LocalDateTime startTime, LocalDateTime endTime,
                                 MetricEnums.MetricAccuracy from, MetricEnums.MetricAccuracy to,
                                 String metricName, LocalDateTime cleanBefore) {
        String timeKey = to.timePattern.format(startTime);
        log.info("do merge metric from:{} ,to:{} timeKey:{} ,from accuracy:{} ,to accuracy:{} ",
                startTime.format(from.timePattern), endTime.format(from.timePattern),
                timeKey, from, to);

        // 一个指标，可能存在很多tag，tag分维如果有上千之后，再加上时间维度，数据量级就可能非常大，所以我们在数据库中先根据tag分组一下
        // 这样驻留在内存中的数据就没有tag维度，避免内存中数据量过大
        String sql = "select tags_md5 from :table where name= :name and create_time >= :startTime and create_time <= :endTime group by tags_md5";
        List<String> tags = databaseClient.sql(sql)
                .bind("table", Objects.requireNonNull(relationalMappingContext.getPersistentEntity(from.handleClazz)).getTableName())
                .bind("name", metricName)
                .bind("startTime", startTime)
                .bind("endTime", endTime)
                .map((row) -> row.get("tags_md5", String.class)).all()
                .collectList().block();

        assert tags != null;
        tags.forEach(tagsMd5 -> mergeTimesSpaceWithTag(
                startTime, endTime, from, to, metricName, tagsMd5, timeKey, cleanBefore
        ));
    }

    private void mergeTimesSpaceWithTag(LocalDateTime startTime, LocalDateTime endTime,
                                        MetricEnums.MetricAccuracy from, MetricEnums.MetricAccuracy to,
                                        String metricName, String tagsMd5, String timeKey, LocalDateTime cleanBefore) {

        Criteria criteria = Criteria.where(SysMetric.NAME).is(metricName)
                .and(SysMetric.TAGS_MD5).is(tagsMd5)
                .and(SysMetric.CREATE_TIME).greaterThan(startTime)
                .and(SysMetric.CREATE_TIME).lessThan(endTime);

        r2dbcEntityTemplate.select(Query.query(criteria).sort(Sort.by(SysMetric.TIME_KEY)), from.handleClazz)
                .collectList()
                .doOnNext((Consumer<List<? extends SysMetric>>) metrics -> {
                    SysMetric first = metrics.get(0);

                    List<SysMetric> mergedList = Lists.newArrayList();

                    if (first.getType() == Meter.Type.TIMER) {
                        metrics.sort(Comparator.comparing(SysMetric::getTimeKey));
                        // for timer , need group by
                        List<SysMetric> countList = Lists.newArrayList();
                        List<SysMetric> totalTimeList = Lists.newArrayList();
                        List<SysMetric> maxList = Lists.newArrayList();
                        SysMetricTag metricTag = metricTagService.fromKey(metricName).block();
                        metrics.forEach(metric -> {
                            String subtype = mapMetric(metric, metricTag).getTags().get(MetricEnums.TimeSubType.timer_type);
                            if (MetricEnums.TimeSubType.COUNT.metricKey.equals(subtype)) {
                                countList.add(metric);
                            } else if (MetricEnums.TimeSubType.TIME.metricKey.equals(subtype)) {
                                totalTimeList.add(metric);
                            } else if (MetricEnums.TimeSubType.MAX.metricKey.equals(subtype)) {
                                maxList.add(metric);
                            }
                        });
                        if (!countList.isEmpty()) {
                            mergedList.add(mergeMetrics(countList, countList.get(0), SUM, to.handleClazz));
                        }
                        if (!totalTimeList.isEmpty()) {
                            mergedList.add(mergeMetrics(totalTimeList, totalTimeList.get(0), SUM, to.handleClazz));
                        }
                        if (!maxList.isEmpty()) {
                            mergedList.add(mergeMetrics(maxList, maxList.get(0), MAX, to.handleClazz));
                        }
                    } else {
                        mergedList.add(mergeMetrics(metrics, first, first.getType() == Meter.Type.COUNTER ? SUM : AVG, to.handleClazz));
                    }

                    mergedList.forEach(mergedMetric -> {
                        mergedMetric.setTimeKey(timeKey);
                        mergedMetric.setCreateTime(first.getCreateTime());
                        log.info("merged metric: {}", JSONObject.toJSONString(mergedMetric));

                        r2dbcEntityTemplate.insert(mergedMetric)
                                .map(sysMetric -> 0L)
                                .onErrorResume(DuplicateKeyException.class, ex -> {
                                    // update on duplicate
                                    // for newer data, I will merge many times
                                    Criteria duplicateCriteria = Criteria.where(SysMetric.NAME).is(mergedMetric.getName())
                                            .and(SysMetric.TAGS_MD5).is(mergedMetric.getTagsMd5())
                                            .and(SysMetric.TIME_KEY).is(mergedMetric.getTimeKey());
                                    return r2dbcEntityTemplate.update(Query.query(duplicateCriteria), Update.update(
                                            SysMetric.VALUE, mergedMetric.getValue()
                                    ), to.handleClazz);
                                }).subscribe();

                    });

                    List<Long> needRemoveIds = metrics.stream()
                            .filter(metric -> metric.getCreateTime().isBefore(cleanBefore))
                            .map(SysMetric::getId)
                            .collect(Collectors.toList());

                    if (!needRemoveIds.isEmpty()) {
                        r2dbcEntityTemplate.delete(from.handleClazz)
                                .matching(Query.query(Criteria.where(SysMetric.ID).in(needRemoveIds)))
                                .all().subscribe();
                    }
                }).subscribe();


    }

    private SysMetric mergeMetrics(List<? extends SysMetric> metrics,
                                   SysMetric first, Function<List<? extends SysMetric>, Double> mergeFunc,
                                   Class<? extends SysMetric> toType) {
        SysMetric container = (SysMetric) ReflectUtil.newInstance(toType);
        BeanUtils.copyProperties(first, container);
        container.setValue(mergeFunc.apply(metrics));
        return container;
    }

    private static final Function<List<? extends SysMetric>, Double> AVG = metrics ->
            metrics.stream()
                    .map(SysMetric::getValue)
                    .reduce(0D, Double::sum) / metrics.size();

    private static final Function<List<? extends SysMetric>, Double> SUM = metrics ->
            metrics.stream()
                    .map(SysMetric::getValue)
                    .reduce(0D, Double::sum);

    private static final Function<List<? extends SysMetric>, Double> MAX = metrics ->
            metrics.stream()
                    .map(SysMetric::getValue)
                    .reduce(0D, Double::max);


    ////////  metric producer start   /////////
    public void onMeter(String timeKey, LocalDateTime time, Meter meter) {
        Meter.Type type = meter.getId().getType();

        if (type == Meter.Type.GAUGE) {
            SysMetricTag metricTag = metricTagService.fromMeter(meter, null).block();
            SysMetricMinute metric = makeMetric(timeKey, time, meter, metricTag, null);
            saveGauge(metric, (Gauge) meter);
        } else if (type == Meter.Type.COUNTER) {
            SysMetricTag metricTag = metricTagService.fromMeter(meter, null).block();
            SysMetricMinute metric = makeMetric(timeKey, time, meter, metricTag, null);
            double count;
            if (meter instanceof Counter) {
                count = ((Counter) meter).count();
            } else if (meter instanceof FunctionCounter) {
                count = ((FunctionCounter) meter).count();
            } else {
                log.warn("unknown counter type:{}", meter.getClass());
                return;
            }
            saveCounter(metric, metric.getTagsMd5(), count);
        } else if (type == Meter.Type.TIMER) {
            Timer timer = (Timer) meter;
            // 三个分量，分别用三个指标存储
            long count = timer.count();
            double totalTime = timer.totalTime(TimeUnit.MILLISECONDS);
            double max = timer.max(TimeUnit.MILLISECONDS);

            SysMetricTag metricTagTime = metricTagService.fromMeter(meter, MetricEnums.TimeSubType.TIME).block();
            SysMetricMinute metricTime = makeMetric(timeKey, time, meter, metricTagTime, MetricEnums.TimeSubType.TIME);
            saveCounter(metricTime, metricTime.getTagsMd5(), totalTime);

            SysMetricTag metricTagCount = metricTagService.fromMeter(meter, MetricEnums.TimeSubType.COUNT).block();
            SysMetricMinute metricCount = makeMetric(timeKey, time, meter, metricTagCount, MetricEnums.TimeSubType.COUNT);
            saveCounter(metricCount, metricCount.getTagsMd5(), (double) count);

            SysMetricTag metricTagMax = metricTagService.fromMeter(meter, MetricEnums.TimeSubType.MAX).block();
            SysMetricMinute metricMax = makeMetric(timeKey, time, meter, metricTagMax, MetricEnums.TimeSubType.MAX);
            metricMax.setValue(max);
            saveMetric(metricMax);
        }

    }


    private void saveGauge(SysMetricMinute metric, Gauge gauge) {
        double value = gauge.value();
        if (Double.isNaN(value)) {
            // 暂时还不确认为啥有NaN出现
            log.error("error gauge for metric: {}", JSONObject.toJSONString(metric));
            value = 0.0;
        }
        metric.setValue(value);
        saveMetric(metric);
    }

    private final Map<String, Double> lastValues = Maps.newConcurrentMap();

    private void saveCounter(SysMetricMinute metric, String uniKey, Double nowCount) {
        Double history = lastValues.get(uniKey);
        if (history == null) {
            history = 0D;
        }

        double add = nowCount - history;
        if (add < 0) {
            // maybe reset??
            add = 0;
        }
        metric.setValue(add);
        lastValues.put(uniKey, nowCount);
        saveMetric(metric);
    }

    private void saveMetric(SysMetricMinute metric) {
        metric.setCreateTime(LocalDateTime.now());
        sysMetricMinuteMapper.save(metric).subscribe();
    }

    private SysMetricMinute makeMetric(String timeKey, LocalDateTime time, Meter meter,
                                       SysMetricTag metricTag, MetricEnums.TimeSubType timerType) {
        Meter.Id id = meter.getId();
        SysMetricMinute metric = new SysMetricMinute();
        metric.setName(id.getName());
        metric.setCreateTime(time);

        metricTagService.setupTag(metricTag, meter, metric, timerType);
        metric.setType(meter.getId().getType());
        metric.setTimeKey(timeKey);
        return metric;
    }

    ////////  metric producer end   /////////

    private static class MergeScanStartCtr {
        private LocalDateTime lastFull;
        private final int fullDurationMinute;
        private final int scanStartBeforeMinute;

        public MergeScanStartCtr(int fullDurationMinute, int scanStartBeforeMinute) {
            this.fullDurationMinute = fullDurationMinute;
            this.scanStartBeforeMinute = scanStartBeforeMinute;
        }

        public LocalDateTime scanStart() {
            if (lastFull == null) {
                lastFull = LocalDateTime.now();
                return null;
            }
            LocalDateTime now = LocalDateTime.now();
            if (lastFull.plusMinutes(fullDurationMinute).isAfter(now)) {
                return now.minusMinutes(scanStartBeforeMinute);
            }
            lastFull = now;
            return null;
        }
    }

    public Mono<Long> deleteMetric(String name) {
        return Flux.just(SysMetricDay.class, SysMetricHour.class, SysMetricMinute.class)
                .flatMap((clazz) -> r2dbcEntityTemplate.delete(
                        Query.query(Criteria.where(SysMetric.NAME).is(name)),
                        clazz
                )).reduce(Long::sum);
    }


    @SuppressWarnings("unchecked")
    public <T extends SysMetric> SysMetricBaseMapper<T> chooseDao(MetricEnums.MetricAccuracy accuracy) {
        return switch (accuracy) {
            case days -> (SysMetricBaseMapper<T>) metricDayMapper;
            case hours -> (SysMetricBaseMapper<T>) metricHourMapper;
            default -> (SysMetricBaseMapper<T>) metricMinuteMapper;
        };
    }

    public <R> Flux<R> eachDao(Function<SysMetricBaseMapper<SysMetric>, Mono<R>> func) {
        return Flux.just(chooseDao(MetricEnums.MetricAccuracy.days),
                        chooseDao(MetricEnums.MetricAccuracy.hours),
                        chooseDao(MetricEnums.MetricAccuracy.minutes))
                .flatMap(func);
    }
}
