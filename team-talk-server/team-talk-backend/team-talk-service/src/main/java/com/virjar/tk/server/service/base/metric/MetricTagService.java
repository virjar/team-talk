package com.virjar.tk.server.service.base.metric;

import com.virjar.tk.server.entity.metric.Metric;
import com.virjar.tk.server.entity.metric.MetricDay;
import com.virjar.tk.server.entity.metric.MetricTag;
import com.virjar.tk.server.utils.Md5Utils;
import com.virjar.tk.server.utils.ServerIdentifier;
import com.virjar.tk.server.mapper.metric.MetricTagMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.virjar.tk.server.service.base.BroadcastService;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MetricTagService {
    private static final String TAG_SERVER_ID = "serverId";

    private static final String TAG_TIMER_TYPE = MetricEnums.TimeSubType.timer_type;

    private static final int TAG_SLOT_COUNT = 5;
    private final Map<String, MetricTag> tagMap = Maps.newConcurrentMap();

    @Resource
    private MetricTagMapper metricTagMapper;


    @PostConstruct
    public void loadAll() {
        metricTagMapper.selectList(new QueryWrapper<>()).forEach(metricTag -> tagMap.put(metricTag.getName(), metricTag));

        BroadcastService.register(BroadcastService.Topic.METRIC_TAG, () -> {
            tagMap.clear();
            metricTagMapper.selectList(new QueryWrapper<>()).forEach(metricTag -> tagMap.put(metricTag.getName(), metricTag));
        });
    }

    public List<String> metricNames() {
        return new ArrayList<>(new TreeSet<>(tagMap.keySet()));
    }

    public List<MetricTag> tagList() {
        ArrayList<MetricTag> metricTags = new ArrayList<>(tagMap.values());
        metricTags.sort(Comparator.comparing(MetricTag::getName));
        return metricTags;
    }

    public MetricTag fromKey(String metricName) {
        if (tagMap.containsKey(metricName)) {
            return tagMap.get(metricName);
        }
        return metricTagMapper.selectOne(new QueryWrapper<MetricTag>().eq(MetricTag.NAME, metricName));
    }

    public MetricTag fromMeter(Meter meter, MetricEnums.TimeSubType timerType) {
        Meter.Id meterId = meter.getId();
        String key = meterId.getName();
        if (tagMap.containsKey(key)) {
            return tagMap.get(key);
        }

        MetricTag metricTag = metricTagMapper.selectOne(new QueryWrapper<MetricTag>().eq(MetricTag.NAME, key));
        if (metricTag == null) {
            metricTag = createTag(key, wrapTagList(meterId.getTags(), timerType));
        }
        tagMap.put(key, metricTag);
        return metricTag;
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

    public <T extends Metric> QueryWrapper<T> wrapQueryWithTags(QueryWrapper<T> queryWrapper,
                                                                Map<String, String> tags,
                                                                MetricTag metricTag) {
        if (tags == null || tags.isEmpty()) {
            return queryWrapper;
        }
        String tag1Name = metricTag.getTag1Name();
        String s = tags.get(tag1Name);
        if (s != null) {
            queryWrapper.eq(MetricDay.TAG1, s);
        }

        String tag2Name = metricTag.getTag2Name();
        s = tags.get(tag2Name);
        if (s != null) {
            queryWrapper.eq(MetricDay.TAG2, s);
        }

        String tag3Name = metricTag.getTag3Name();
        s = tags.get(tag3Name);
        if (s != null) {
            queryWrapper.eq(MetricDay.TAG3, s);
        }

        String tag4Name = metricTag.getTag4Name();
        s = tags.get(tag4Name);
        if (s != null) {
            queryWrapper.eq(MetricDay.TAG4, s);
        }

        String tag5Name = metricTag.getTag5Name();
        s = tags.get(tag5Name);
        if (s != null) {
            queryWrapper.eq(MetricDay.TAG5, s);
        }
        return queryWrapper;
    }

    public void setupTag(MetricTag metricTag, Meter meter, Metric metric, MetricEnums.TimeSubType timerType) {
        StringBuilder uniformKey = new StringBuilder(meter.getId().getName());

        wrapTagList(meter.getId().getTags(), timerType)
                .stream()
                .sorted(Comparator.comparing(Tag::getKey))
                .forEach(tag -> {
                    String key = tag.getKey();
                    uniformKey.append(key).append(tag.getValue()).append("&");
                    if (key.equals(metricTag.getTag1Name())) {
                        metric.setTag1(tag.getValue());
                    } else if (key.equals(metricTag.getTag2Name())) {
                        metric.setTag2(tag.getValue());
                    } else if (key.equals(metricTag.getTag3Name())) {
                        metric.setTag3(tag.getValue());
                    } else if (key.equals(metricTag.getTag4Name())) {
                        metric.setTag4(tag.getValue());
                    } else if (key.equals(metricTag.getTag5Name())) {
                        metric.setTag5(tag.getValue());
                    }
                });
        metric.setTagsMd5(Md5Utils.md5Hex(uniformKey.toString()));
    }

    private MetricTag createTag(String key, List<Tag> tags) {
        if (tags.size() > TAG_SLOT_COUNT) {
            throw new IllegalStateException("tags size must less than :" + TAG_SLOT_COUNT);
        }
        MetricTag metricTag = new MetricTag();
        metricTag.setName(key);
        tags = tags.stream().sorted(Comparator.comparing(Tag::getKey)).collect(Collectors.toList());

        for (int i = 0; i < tags.size(); i++) {
            String tagName = tags.get(i).getKey();
            switch (i) {
                case 0:
                    metricTag.setTag1Name(tagName);
                    break;
                case 1:
                    metricTag.setTag2Name(tagName);
                    break;
                case 2:
                    metricTag.setTag3Name(tagName);
                    break;
                case 3:
                    metricTag.setTag4Name(tagName);
                    break;
                case 4:
                    metricTag.setTag5Name(tagName);
                    break;
            }
        }
        try {
            metricTagMapper.insert(metricTag);
        } catch (DuplicateKeyException ignore) {
        }
        return metricTagMapper.selectOne(new QueryWrapper<MetricTag>().eq(MetricTag.NAME, key));
    }
}
