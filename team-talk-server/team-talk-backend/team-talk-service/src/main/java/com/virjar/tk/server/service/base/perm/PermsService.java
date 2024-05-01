package com.virjar.tk.server.service.base.perm;

import com.virjar.tk.server.entity.CommonRes;
import com.virjar.tk.server.entity.UserInfo;
import com.virjar.tk.server.service.base.dbcache.DbCacheManager;
import com.virjar.tk.server.service.base.dbcache.exs.UserEx;
import com.virjar.tk.server.system.AppContext;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.LineReader;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PermsService implements ApplicationListener<WebServerInitializedEvent> {

    @Resource
    private DbCacheManager dbCacheManager;

    @SuppressWarnings({"rawtypes"})
    private final Map<String, Permission> allPermissionsWithScope = Maps.newHashMap();

    @SuppressWarnings({"rawtypes"})
    private final Map<Class<?>, Permission> allPermissionWithType = Maps.newHashMap();

    private static final Splitter splitter = Splitter.on(':').omitEmptyStrings().trimResults();


    public List<String> permissionScopes() {
        return new ArrayList<>(Sets.newTreeSet(allPermissionsWithScope.keySet()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public CommonRes<List<String>> perms(String scope) {
        return CommonRes.ofPresent(allPermissionsWithScope.get(scope))
                .transform((Function<Permission, List<String>>) input -> new ArrayList<String>(input.perms()));
    }

    public <T> QueryWrapper<T> filter(Class<T> type, QueryWrapper<T> sql) {
        doAction(type, new AuthAction<Void, T>() {
            @Override
            public Void applyAuth(Permission<T> permission, Collection<String> perms) {
                permission.filter(perms, sql);
                return null;
            }

            @Override
            public Void pass() {
                return null;
            }
        });
        return sql;
    }

    public <T> List<T> filter(Class<T> type, List<T> input) {
        return doAction(type, new AuthAction<List<T>, T>() {
            @Override
            public List<T> applyAuth(Permission<T> permission, Collection<String> perms) {
                return permission.filter(perms, input);
            }

            @Override
            public List<T> pass() {
                return input;
            }
        });
    }

    public <T> boolean hasPermission(Class<T> type, T t) {
        return doAction(type, new AuthAction<Boolean, T>() {
            @Override
            public Boolean applyAuth(Permission<T> permission, Collection<String> perms) {
                return permission.hasPermission(perms, t);
            }

            @Override
            public Boolean pass() {
                return true;
            }
        });
    }


    private <T1, T2> T1 doAction(Class<T2> type, AuthAction<T1, T2> function) {
        UserInfo user = AppContext.getUser();
        if (user == null || user.getIsAdmin()) {
            return function.pass();
        }
        @SuppressWarnings({"unchecked"})
        Permission<T2> permission = (Permission<T2>) allPermissionWithType.get(type);
        UserEx userEx = dbCacheManager.getUserCacheWithName().getExtension(user.getUserName());
        if (permission != null) {
            Collection<String> perms = userEx.perms.getOrDefault(permission.scope(), Collections.emptyList());
            return function.applyAuth(permission, perms);
        }
        throw new IllegalStateException("no permission handler declared for type: " + type);
    }

    private interface AuthAction<T1, T2> {
        T1 applyAuth(Permission<T2> permission, Collection<String> perms);

        T1 pass();

    }

    public static String rebuildExp(Map<String, Collection<String>> config) {
        StringBuilder sb = new StringBuilder();

        TreeMap<String, Collection<String>> sorted = new TreeMap<>(config);

        sorted.forEach((scope, permItems) -> {
            StringBuilder sbOfScope = new StringBuilder().append(scope);
            for (String item : permItems.stream().sorted(String::compareTo)
                    .collect(Collectors.toList())) {
                if (sbOfScope.length() > 256) {
                    sb.append(sbOfScope).append("\n");
                    sbOfScope = new StringBuilder().append(scope);
                }
                sbOfScope.append(":").append(item);
            }
            sb.append(sbOfScope).append("\n");
        });
        return sb.toString();
    }

    @SuppressWarnings("UnstableApiUsage")
    @SneakyThrows(IOException.class)
    public static Map<String, Collection<String>> parseExp(String config, boolean safe) {
        HashMultimap<@Nullable String, @Nullable String> ret = HashMultimap.create();
        LineReader lineReader = new LineReader(new StringReader(config));
        String line;
        while ((line = lineReader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            AtomicReference<String> ref = new AtomicReference<>(null);
            AtomicInteger count = new AtomicInteger(0);
            splitter.splitToStream(line).forEach(s -> {
                if (ref.get() == null) {
                    ref.set(s);
                    return;
                }
                ret.put(ref.get(), s);
                count.incrementAndGet();
            });

            if (count.get() == 0) {
                if (safe) {
                    log.warn("error perms exp: " + line);
                } else {
                    throw new IllegalArgumentException("error perms exp: " + line);
                }
            }
        }
        return ret.asMap();
    }


    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        event.getApplicationContext().getBeansOfType(Permission.class)
                .values().forEach(permission -> {
                    allPermissionsWithScope.put(permission.scope(), permission);
                    allPermissionWithType.put(permission.getClazz(), permission);
                });
    }
}
