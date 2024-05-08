package com.virjar.tk.server.sys.service.metric;

import com.virjar.tk.server.sys.entity.metric.SysMetric;
import com.virjar.tk.server.sys.entity.metric.SysMetricDay;
import com.virjar.tk.server.sys.entity.metric.SysMetricTag;
import com.virjar.tk.server.utils.Md5Utils;
import com.virjar.tk.server.utils.ServerIdentifier;
import com.virjar.tk.server.sys.mapper.metric.SysMetricTagMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.virjar.tk.server.sys.service.BroadcastService;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MetricTagService {
    private static final String TAG_SERVER_ID = "serverId";

    private static final String TAG_TIMER_TYPE = MetricEnums.TimeSubType.timer_type;

    private static final int TAG_SLOT_COUNT = 5;
    private final Map<String, SysMetricTag> tagMap = Maps.newConcurrentMap();

    @Resource
    private SysMetricTagMapper sysMetricTagMapper;


    @PostConstruct
    public void loadAll() {
        sysMetricTagMapper.selectList(new QueryWrapper<>()).forEach(SysMetricTag -> tagMap.put(SysMetricTag.getName(), SysMetricTag));

        BroadcastService.register(BroadcastService.Topic.METRIC_TAG, () -> {
            tagMap.clear();
            sysMetricTagMapper.selectList(new QueryWrapper<>()).forEach(SysMetricTag -> tagMap.put(SysMetricTag.getName(), SysMetricTag));
        });
    }

    public List<String> metricNames() {
        return new ArrayList<>(new TreeSet<>(tagMap.keySet()));
    }

    public List<SysMetricTag> tagList() {
        ArrayList<SysMetricTag> SysMetricTags = new ArrayList<>(tagMap.values());
        SysMetricTags.sort(Comparator.comparing(SysMetricTag::getName));
        return SysMetricTags;
    }

    public SysMetricTag fromKey(String metricName) {
        if (tagMap.containsKey(metricName)) {
            return tagMap.get(metricName);
        }
        return sysMetricTagMapper.selectOne(new QueryWrapper<SysMetricTag>().eq(SysMetricTag.NAME, metricName));
    }

    public SysMetricTag fromMeter(Meter meter, MetricEnums.TimeSubType timerType) {
        Meter.Id meterId = meter.getId();
        String key = meterId.getName();
        if (tagMap.containsKey(key)) {
            return tagMap.get(key);
        }

        SysMetricTag sysMetricTag = sysMetricTagMapper.selectOne(new QueryWrapper<SysMetricTag>()
                .eq(SysMetricTag.NAME, key));
        if (sysMetricTag == null) {
            sysMetricTag = createTag(key, wrapTagList(meterId.getTags(), timerType));
        }
        tagMap.put(key, sysMetricTag);
        return sysMetricTag;
    }

    private List<Tag> wrapTagList(List<Tag> list, MetricEnums.TimeSubType timerType) {
        ArrayList<Tag> ret = Lists.newArrayListWithExpectedSize(list.size() + 1);
        ret.add(Tag.of(TAG_SERVER_ID, ServerIdentifier.id()));
        if (timerType != null) {
            ret.add(Tag.of(TAG_TIMER_TYPE, timerType.metricKey));
        }
        ret.addAll(list);
        return ret;
    }

    public <T extends SysMetric> QueryWrapper<T> wrapQueryWithTags(QueryWrapper<T> queryWrapper,
                                                                   Map<String, String> tags,
                                                                   SysMetricTag SysMetricTag) {
        if (tags == null || tags.isEmpty()) {
            return queryWrapper;
        }
        String tag1Name = SysMetricTag.getTag1Name();
        String s = tags.get(tag1Name);
        if (s != null) {
            queryWrapper.eq(SysMetricDay.TAG1, s);
        }

        String tag2Name = SysMetricTag.getTag2Name();
        s = tags.get(tag2Name);
        if (s != null) {
            queryWrapper.eq(SysMetricDay.TAG2, s);
        }

        String tag3Name = SysMetricTag.getTag3Name();
        s = tags.get(tag3Name);
        if (s != null) {
            queryWrapper.eq(SysMetricDay.TAG3, s);
        }

        String tag4Name = SysMetricTag.getTag4Name();
        s = tags.get(tag4Name);
        if (s != null) {
            queryWrapper.eq(SysMetricDay.TAG4, s);
        }

        String tag5Name = SysMetricTag.getTag5Name();
        s = tags.get(tag5Name);
        if (s != null) {
            queryWrapper.eq(SysMetricDay.TAG5, s);
        }
        return queryWrapper;
    }

    public void setupTag(SysMetricTag SysMetricTag, Meter meter, SysMetric metric, MetricEnums.TimeSubType timerType) {
        StringBuilder uniformKey = new StringBuilder(meter.getId().getName());

        wrapTagList(meter.getId().getTags(), timerType)
                .stream()
                .sorted(Comparator.comparing(Tag::getKey))
                .forEach(tag -> {
                    String key = tag.getKey();
                    uniformKey.append(key).append(tag.getValue()).append("&");
                    if (key.equals(SysMetricTag.getTag1Name())) {
                        metric.setTag1(tag.getValue());
                    } else if (key.equals(SysMetricTag.getTag2Name())) {
                        metric.setTag2(tag.getValue());
                    } else if (key.equals(SysMetricTag.getTag3Name())) {
                        metric.setTag3(tag.getValue());
                    } else if (key.equals(SysMetricTag.getTag4Name())) {
                        metric.setTag4(tag.getValue());
                    } else if (key.equals(SysMetricTag.getTag5Name())) {
                        metric.setTag5(tag.getValue());
                    }
                });
        metric.setTagsMd5(Md5Utils.md5Hex(uniformKey.toString()));
    }

    private SysMetricTag createTag(String key, List<Tag> tags) {
        if (tags.size() > TAG_SLOT_COUNT) {
            throw new IllegalStateException("tags size must less than :" + TAG_SLOT_COUNT);
        }
        SysMetricTag sysMetricTag = new SysMetricTag();
        sysMetricTag.setName(key);
        tags = tags.stream().sorted(Comparator.comparing(Tag::getKey)).collect(Collectors.toList());

        for (int i = 0; i < tags.size(); i++) {
            String tagName = tags.get(i).getKey();
            switch (i) {
                case 0:
                    sysMetricTag.setTag1Name(tagName);
                    break;
                case 1:
                    sysMetricTag.setTag2Name(tagName);
                    break;
                case 2:
                    sysMetricTag.setTag3Name(tagName);
                    break;
                case 3:
                    sysMetricTag.setTag4Name(tagName);
                    break;
                case 4:
                    sysMetricTag.setTag5Name(tagName);
                    break;
            }
        }
        try {
            sysMetricTagMapper.insert(sysMetricTag);
        } catch (DuplicateKeyException ignore) {
        }
        return sysMetricTagMapper.selectOne(new QueryWrapper<SysMetricTag>().eq(SysMetricTag.NAME, key));
    }
}
