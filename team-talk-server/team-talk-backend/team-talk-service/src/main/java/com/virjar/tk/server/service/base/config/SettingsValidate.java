package com.virjar.tk.server.service.base.config;

import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 校验设置配置中前端传递过来的参数
 */
public class SettingsValidate {

    public static String doValidate(String key, String value) {
        if (StringUtils.isBlank(key)) {
            return "key is blank";
        }
        Validator validator = validatorMap.get(key);
        if (validator != null) {
            String error = validator.doValidate(value);
            if (error != null) {
                return error;
            }
        }
        return Configs.validateConfig(key, value);
    }

    private static final Map<String, Validator> validatorMap = new HashMap<String, Validator>() {{
        put(Settings.outIpTestUrl.key, new URLValidator());
    }};


    public interface Validator {
        String doValidate(String value);
    }

    private static class EnumValidator implements Validator {
        private final Set<String> enums;

        public EnumValidator(Collection<String> enums) {
            this.enums = Sets.newHashSet(enums);
        }

        @Override
        public String doValidate(String value) {
            if (enums.contains(value)) {
                return null;
            }
            return "not in enum list: " + value;
        }
    }

    private static class URLValidator implements Validator {

        @Override
        public String doValidate(String value) {
            if (StringUtils.isBlank(value)) {
                return null;
            }
            try {
                new URL(value);
                return null;
            } catch (MalformedURLException e) {
                return e.getMessage();
            }
        }
    }


    private static class IntegerRange implements Validator {
        private final int min;
        private final int max;

        public IntegerRange(int min, int max) {
            this.min = min;
            this.max = max;
        }

        @Override
        public String doValidate(String value) {
            try {
                int i = Integer.parseInt(value);
                if (i < min) {
                    return "value: " + value + " must grater :" + min;
                }
                if (i > max) {
                    return "value: " + value + " must less :" + max;
                }
                return null;
            } catch (NumberFormatException e) {
                return e.getMessage();
            }
        }
    }
}
