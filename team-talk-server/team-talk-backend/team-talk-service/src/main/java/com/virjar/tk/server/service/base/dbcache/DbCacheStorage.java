package com.virjar.tk.server.service.base.dbcache;

import com.virjar.tk.server.utils.ReflectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DbCacheStorage<M, E> {
    private Map<String, DbWrapper<M, E>> cacheMap = Maps.newConcurrentMap();

    private final String keyField;
    private final String keyFieldCamel;
    private final BaseMapper<M> baseMapper;
    private final UpdateHandler<M, E> updateHandler;

    public DbCacheStorage(String keyField, BaseMapper<M> baseMapper) {
        this(keyField, baseMapper, null);
    }

    public DbCacheStorage(String keyField, BaseMapper<M> baseMapper, UpdateHandler<M, E> updateHandler) {
        this.keyField = keyField;
        this.baseMapper = baseMapper;
        this.updateHandler = updateHandler;
        this.keyFieldCamel = lineToCamel(keyField);
    }

    public M getModeWithCache(String key) {
        if (key == null) {
            return null;
        }
        DbWrapper<M, E> wrapper = cacheMap.get(key);
        if (wrapper == null) {
            return null;
        }
        return wrapper.m;
    }

    public M getMode(String key) {
        DbWrapper<M, E> wrapper = getWrapper(key);
        if (wrapper != null) {
            return wrapper.m;
        }
        return null;
    }

    public E getExtension(String key) {
        DbWrapper<M, E> wrapper = getWrapper(key);
        if (wrapper != null) {
            return wrapper.e;
        }
        return null;
    }

    public DbWrapper<M, E> getWrapper(String key) {
        if (key == null) {
            return null;
        }
        DbWrapper<M, E> mDbWrapper = cacheMap.get(key);
        if (mDbWrapper != null) {
            return mDbWrapper;
        }
        M m = baseMapper.selectOne(new QueryWrapper<M>().eq(keyField, key));
        if (m != null) {
            mDbWrapper = new DbWrapper<>(m);
            if (updateHandler != null) {
                mDbWrapper.e = updateHandler.doUpdate(m, null);
            }
            cacheMap.put(key, mDbWrapper);
        }
        return mDbWrapper;
    }


    public void updateAll() {
        Map<String, DbWrapper<M, E>> newCacheMap = Maps.newConcurrentMap();
        baseMapper.selectList(new QueryWrapper<>()).forEach(m -> {
            Object keyObj;
            try {
                keyObj = ReflectUtil.getFieldValue(m, keyFieldCamel);
            } catch (NoSuchFieldError error) {
                keyObj = ReflectUtil.getFieldValue(m, keyField);
            }
            if (keyObj == null) {
                return;
            }
            String key = keyObj.toString();

            if (StringUtils.isBlank(key)) {
                return;
            }
            DbWrapper<M, E> wrapper = cacheMap.get(key);
            if (wrapper != null) {
                if (updateHandler != null) {
                    wrapper.e = updateHandler.doUpdate(m, wrapper.e);
                }
                wrapper.m = m;
            } else {
                wrapper = new DbWrapper<>(m);
                if (updateHandler != null) {
                    wrapper.e = updateHandler.doUpdate(m, null);
                }
            }
            newCacheMap.put(key, wrapper);
        });
        cacheMap = newCacheMap;
    }

    public Collection<E> extensions() {
        return cacheMap.values().stream().map(meDbWrapper -> meDbWrapper.e).collect(Collectors.toList());
    }

    public List<M> models() {
        return cacheMap.values().stream().map(meDbWrapper -> meDbWrapper.m).collect(Collectors.toList());
    }

    public interface UpdateHandler<M, E> {
        E doUpdate(M m, E e);
    }

    public static String lineToCamel(String str) {
        StringBuilder sb = new StringBuilder();
        boolean preUnderLine = false;
        for (char ch : str.toCharArray()) {
            if (ch == '_') {
                preUnderLine = true;
                continue;
            }
            sb.append(preUnderLine ? Character.toUpperCase(ch) : ch);
            preUnderLine = false;
        }
        return sb.toString();
    }
}
