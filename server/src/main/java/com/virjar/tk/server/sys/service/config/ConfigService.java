package com.virjar.tk.server.sys.service.config;

import com.virjar.tk.server.common.BusinessException;
import com.virjar.tk.server.sys.entity.SysConfig;
import com.virjar.tk.server.sys.mapper.SysConfigMapper;
import com.virjar.tk.server.sys.service.BroadcastService;
import com.virjar.tk.server.sys.service.env.Environment;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.virjar.tk.server.common.BusinessException.SYSTEM.CANNOT_CHANGE_SETTING_FOR_DEMO_SITE;

@Service
public class ConfigService {
    public static final String SYSTEM_SETTINGS = "__teamTalk_system_setting";
    @Resource
    private SysConfigMapper sysConfigMapper;


    public Mono<List<SysConfig>> allConfig() {
        return sysConfigMapper
                .findAllByConfigComment(SYSTEM_SETTINGS)
                .collectList();
    }

    public Mono<List<SysConfig>> setConfigs(@RequestBody Map<String, String> configs) {
        if (Environment.isDemoSite) {
            return CANNOT_CHANGE_SETTING_FOR_DEMO_SITE.m();
        }
        String errorMsg = null;
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String msg = SettingsValidate.doValidate(entry.getKey(), entry.getValue());
            if (msg != null) {
                errorMsg = "error config: " + entry.getKey() + " " + msg;
                break;
            }
        }
        if (StringUtils.isNotBlank(errorMsg)) {
            return BusinessException.errorM(errorMsg);
        }

        if (configs.isEmpty()) {
            return BusinessException.errorM("no config passed");
        }

        return Flux.fromIterable(configs.entrySet())
                .parallel()
                .flatMap((entry) -> setConfig(entry.getKey(), entry.getValue()))
                .collectSortedList(Comparator.comparing(SysConfig::getConfigKey));
    }

    public Mono<SysConfig> setConfig(String key, String value) {
        if (key.startsWith("__")) {
            return BusinessException.errorM("can not setup system internal properties :" + key);
        }

        SysConfig sysConfig = new SysConfig();
        sysConfig.setConfigKey(key);
        sysConfig.setConfigValue(value);
        sysConfig.setConfigComment(SYSTEM_SETTINGS);

        return sysConfigMapper.save(sysConfig)
                .doOnSuccess((it) -> BroadcastService.triggerEvent(BroadcastService.Topic.CONFIG));
    }

    public void reloadConfig() {
        sysConfigMapper.findAllByConfigComment(SYSTEM_SETTINGS)
                .collectList()
                .subscribe(Configs::refreshConfig);
    }
}
