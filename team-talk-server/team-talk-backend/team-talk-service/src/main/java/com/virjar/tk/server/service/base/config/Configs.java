package com.virjar.tk.server.service.base.config;

import com.virjar.tk.server.entity.SysConfig;
import com.virjar.tk.server.utils.CommonUtils;
import com.virjar.tk.server.service.base.env.Constants;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.util.TypeUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class Configs {
    private static final Properties applicationProperties = new Properties() {
        {
            try {
                InputStream stream = Configs.class.getClassLoader().getResourceAsStream(Constants.APPLICATION_PROPERTIES);
                if (stream != null) {
                    load(stream);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    };

    private static Map<String, String> dbConfigs = Maps.newHashMap();


    public static String getConfig(String key, String defaultValue) {
        String config = getConfig(key);
        if (StringUtils.isBlank(config)) {
            return defaultValue;
        }
        return config;
    }

    public static String getConfig(String key) {
        if (dbConfigs.containsKey(key)) {
            // 无论如何，只要数据库存在此项配置，那么一定使用数据库
            // 实现一个完善的overlay 配置中心是非常困难的
            return dbConfigs.get(key);
        }
        return applicationProperties.getProperty(key);
    }


    public static void refreshConfig(List<SysConfig> configList) {
        Map<String, String> copyOnWriteConfigs = new HashMap<>();
        for (SysConfig sysConfig : configList) {
            copyOnWriteConfigs.put(sysConfig.getConfigKey(), sysConfig.getConfigValue());
        }
        // diff and notify config change listener
        boolean hasChange = false;
        if (dbConfigs.size() != copyOnWriteConfigs.size()) {
            hasChange = true;
        } else {
            for (String key : dbConfigs.keySet()) {
                if (!copyOnWriteConfigs.containsKey(key)) {
                    hasChange = true;
                    break;
                }
                if (!Objects.equals(dbConfigs.get(key), copyOnWriteConfigs.get(key))) {
                    hasChange = true;
                    break;
                }
            }
        }
        dbConfigs = copyOnWriteConfigs;
        if (hasChange) {
            notifyConfigChange();
        }
    }


    public static int getInt(String key, int defaultValue) {
        String config = getConfig(key);
        if (StringUtils.isBlank(config)) {
            return defaultValue;
        }
        return NumberUtils.toInt(key, defaultValue);
    }

    public interface ConfigChangeListener {
        void onConfigChange();
    }


    private static final AtomicInteger sListenerCount = new AtomicInteger();

    private static final Set<ConfigChangeListener> configChangeListeners = Sets.newConcurrentHashSet();

    private static final int MAX_LISTENER_COUNT = 256;

    public static void addConfigChangeListener(ConfigChangeListener configChangeListener) {
        if (sListenerCount.incrementAndGet() > MAX_LISTENER_COUNT) {
            // 高并发情况下，监听器理论上都应该是静态的
            // 否则容易让那个这个对象被撑爆
            throw new IllegalStateException("to many config change listener register");
        }
        if (sListenerCount.get() > MAX_LISTENER_COUNT / 2) {
            log.warn("to many config change listener register");
        }
        configChangeListeners.add(configChangeListener);
    }

    private static void notifyConfigChange() {
        List<ConfigChangeListener> pending = Lists.newLinkedList();
        for (ConfigChangeListener configChangeListener : configChangeListeners) {
            if (configChangeListener instanceof MonitorConfigChangeListener) {
                // monitor需要先执行
                configChangeListener.onConfigChange();
            } else {
                pending.add(configChangeListener);
            }
        }
        for (ConfigChangeListener configChangeListener : pending) {
            configChangeListener.onConfigChange();
        }
    }

    public interface ConfigFetcher<T> {
        void fetch(T value);
    }

    public static <T> void addConfigFetcher(ConfigFetcher<T> configFetcher, String configKey, T defaultValue, Class<?> superClassGenricType) {
        addConfigFetcher(configFetcher, configKey, defaultValue, null, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> getSuperClassGenericType(Class<?> clazz) {
        Type genType = clazz.getGenericSuperclass();
        if (!(genType instanceof ParameterizedType)) {
            return (Class<T>) Object.class;
        }
        Type[] params = ((ParameterizedType) genType).getActualTypeArguments();
        if (0 == params.length) {
            return (Class<T>) Object.class;
        } else if (!(params[0] instanceof Class)) {
            return (Class<T>) Object.class;
        } else {
            return (Class<T>) params[0];
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> void addConfigFetcher(ConfigFetcher<T> configFetcher, String configKey, T defaultValue, final TransformFunc<T> transformer, Class<T> valueType) {
        if (valueType == null) {
            valueType = (Class<T>) getSuperClassGenericType(configFetcher.getClass());
        }

        if (valueType.equals(Object.class) && defaultValue != null) {
            valueType = (Class<T>) defaultValue.getClass();
        }

        if (valueType.equals(Object.class) && transformer != null) {
            valueType = getSuperClassGenericType(transformer.getClass());
        }

        ConfigChangeListener configChangeListener = new MonitorConfigChangeListener<>(
                configKey, defaultValue, configFetcher, transformer, valueType
        );
        configChangeListener.onConfigChange();
        addConfigChangeListener(configChangeListener);
    }

    private static final Map<String, MonitorConfigChangeListener<?>> autoTransformerValidators = new HashMap<>();

    public static String validateConfig(String key, String value) {
        MonitorConfigChangeListener<?> monitorConfigChangeListener = autoTransformerValidators.get(key);
        if (monitorConfigChangeListener == null) {
            return null;
        }
        try {
            monitorConfigChangeListener.transform(value);
            return null;
        } catch (Throwable throwable) {
            return CommonUtils.throwableToString(throwable);
        }
    }

    static class MonitorConfigChangeListener<T> implements ConfigChangeListener {
        private String originValue = null;
        private T value = null;

        private final String configKey;
        private final T defaultValue;
        private final ConfigFetcher<T> configFetcher;
        private final TransformFunc<T> transformer;
        private final Class<T> finalValueType;

        @SuppressWarnings("unchecked")
        public MonitorConfigChangeListener(String configKey, T defaultValue, ConfigFetcher<T> configFetcher, TransformFunc<T> transformer, Class<T> finalValueType) {
            this.configKey = configKey;
            this.defaultValue = defaultValue;
            this.configFetcher = configFetcher;
            if (transformer == null) {
                this.transformer = (TransformFunc<T>) defaultTransformFuncInstance;
            } else {
                this.transformer = transformer;
            }
            this.finalValueType = finalValueType;
            autoTransformerValidators.put(configKey, this);
        }

        public T transform(String config) {
            return transformer.apply(config, finalValueType);
        }

        @Override
        public void onConfigChange() {
            String config = getConfig(configKey);
            if (value != null && Objects.equals(originValue, config)) {
                return;
            }
            originValue = config;
            if (config == null) {
                value = defaultValue;
                configFetcher.fetch(defaultValue);
                return;
            }
            T t = transform(config);
            value = t;
            configFetcher.fetch(t);
        }
    }

    private interface TransformFunc<T> {
        T apply(String value, Class<T> type);
    }

    private static final class DefaultTransformFunc implements TransformFunc<Object> {


        @Override
        public Object apply(String value, Class<Object> type) {
            return TypeUtils.cast(value, type, ParserConfig.getGlobalInstance());
        }
    }

    private static final DefaultTransformFunc defaultTransformFuncInstance = new DefaultTransformFunc();

    private static final Map<String, Object> registerConfigValueRecord = Maps.newConcurrentMap();

    public abstract static class ConfigValue<V> {
        public V value;
        public final String key;

        public ConfigValue(String configKey, V defaultValue) {
            this.key = configKey;
            if (registerConfigValueRecord.containsKey(configKey)) {
                Object o = registerConfigValueRecord.get(configKey);
                if (ObjectUtils.notEqual(o, defaultValue)) {
                    // 系统的自动包装监听器在使用的时候不能出现重复，
                    // 这里主要是默认值的设置可能存在歧义，我们认为配置中心在配置缺失状态下默认值策略应该是一致的
                    // 所以这里发现存在相同的配置key的时候，检查一下默认值，如果默认值不相同，那么认为这是错误的
                    throw new IllegalStateException("duplicate config key monitor registered key:" + configKey
                            + " defaultValue1:" + o + "  defaultValue2:" + defaultValue
                            + " monitorClass:" + this.getClass());
                }
                registerConfigValueRecord.put(configKey, defaultValue);
            }
            Class<V> superClassGenericType = getSuperClassGenericType(getClass());
            TransformFunc<V> transformer = transformer();
            addConfigFetcher(value -> ConfigValue.this.value = value,
                    configKey, defaultValue, transformer, superClassGenericType
            );
        }

        protected String configType() {
            return value.getClass().getSimpleName();
        }

        protected TransformFunc<V> transformer() {
            return null;
        }

    }


    public static class StringConfigValue extends ConfigValue<String> {

        public StringConfigValue(String configKey, String defaultValue) {
            super(configKey, defaultValue);
        }
    }

    public static class MultiLineStrConfigValue extends ConfigValue<String> {

        public MultiLineStrConfigValue(String configKey, String defaultValue) {
            super(configKey, defaultValue);
        }

        @Override
        protected String configType() {
            return "multiLine";
        }
    }

    public static class BooleanConfigValue extends ConfigValue<Boolean> {

        public BooleanConfigValue(String configKey, Boolean defaultValue) {
            super(configKey, defaultValue);
        }
    }

    public static class IntegerConfigValue extends ConfigValue<Integer> {
        public IntegerConfigValue(String configKey, Integer defaultValue) {
            super(configKey, defaultValue);
        }
    }

    /**
     * 可变的数字抽象，比IntegerConfigValue更好用，因为他是jdk标准抽象
     */
    public static class NumberIntegerConfigValue extends Number {
        private final IntegerConfigValue configValue;

        public NumberIntegerConfigValue(String configKey, Integer defaultValue) {
            configValue = new IntegerConfigValue(configKey, defaultValue);
        }

        @Override
        public int intValue() {
            return configValue.value;
        }

        @Override
        public long longValue() {
            return configValue.value;
        }

        @Override
        public float floatValue() {
            return configValue.value;
        }

        @Override
        public double doubleValue() {
            return configValue.value;
        }
    }

    public static boolean noneBlank(Iterable<StringConfigValue> collection) {
        for (StringConfigValue value : collection) {
            if (StringUtils.isBlank(value.value)) {
                return false;
            }
        }
        return true;
    }


    public static void addKeyMonitor(String key, ConfigChangeListener changeListener) {
        addKeyMonitor(Lists.newArrayList(key), changeListener);
    }

    /**
     * 监控一些配置的变更，只有当对应的key发生了变化的时候才触发监听函数
     *
     * @param keys           key列表
     * @param changeListener 事件监听
     */
    public static void addKeyMonitor(List<String> keys, ConfigChangeListener changeListener) {
        Map<String, String> data = Maps.newHashMap();
        for (String key : keys) {
            data.put(key, getConfig(key));
        }
        addConfigChangeListener(() -> {
            boolean hasChange = false;
            for (String key : keys) {
                String nowValue = getConfig(key);
                if (!Objects.equals(data.get(key), nowValue)) {
                    hasChange = true;
                    data.put(key, nowValue);
                }
            }
            if (hasChange) {
                changeListener.onConfigChange();
            }
        });
    }

}
