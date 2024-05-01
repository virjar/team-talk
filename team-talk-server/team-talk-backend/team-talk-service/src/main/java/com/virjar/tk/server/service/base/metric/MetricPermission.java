package com.virjar.tk.server.service.base.metric;

import com.virjar.tk.server.entity.metric.MetricDay;
import com.virjar.tk.server.service.base.perm.Permission;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.base.Function;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 控制用户查看指标的权限,
 * 只是一个demo，实际上不应该让普通用户有查看指标的权限
 */
@Component
public class MetricPermission extends Permission<MetricDay> {
    private final Set<String> categoryRegistry = new HashSet<>();
    private final Multimap<String, String> metricMapRegistry = HashMultimap.create();

    public MetricPermission() {
        super(MetricDay.class);
        addDefault("订单模块", "teamTalk.order.create");
        addDefault("订单模块", "teamTalk.order.cancel");

        addDefault("用户模块", "teamTalk.user.login");
        addDefault("用户模块", "teamTalk.user.register");
    }

    private void addDefault(String category, String metricName) {
        categoryRegistry.add(category);
        metricMapRegistry.put(category, metricName);
    }

    @Override
    public String scope() {
        return "metric";
    }

    @Override
    public Collection<String> perms() {
        return categoryRegistry;
    }

    @Override
    public void filter(Collection<String> perms, QueryWrapper<MetricDay> sql) {
        if (perms.isEmpty()) {
            sql.eq(MetricDay.ID, -1);
            return;
        }
        Set<String> hasPermsMetrics = new HashSet<>();
        for (String perm : perms) {
            Collection<String> metricNames = metricMapRegistry.get(perm);
            hasPermsMetrics.addAll(metricNames);
        }
        if (hasPermsMetrics.isEmpty()) {
            sql.eq(MetricDay.ID, -1);
            return;
        }

        sql.and((Function<QueryWrapper<MetricDay>, QueryWrapper<MetricDay>>) input -> {
            input.in(MetricDay.NAME, hasPermsMetrics);
            return input;
        });
    }


    @Override
    public boolean hasPermission(Collection<String> perms, MetricDay metric) {
        return perms.stream().anyMatch(s -> metric.getName().startsWith(s));
    }
}
