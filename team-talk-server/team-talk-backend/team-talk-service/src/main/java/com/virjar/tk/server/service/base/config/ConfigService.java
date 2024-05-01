package com.virjar.tk.server.service.base.config;

import com.virjar.tk.server.entity.CommonRes;
import com.virjar.tk.server.entity.SysConfig;
import com.virjar.tk.server.mapper.SysConfigMapper;
import com.virjar.tk.server.service.base.BroadcastService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service
public class ConfigService {
    public static final String SYSTEM_SETTINGS = "__teamTalk_system_setting";
    @Resource
    private SysConfigMapper sysConfigMapper;



    public CommonRes<List<SysConfig>> allConfig() {
        return CommonRes.success(
                sysConfigMapper.selectList(new QueryWrapper<SysConfig>().eq(SysConfig.CONFIG_COMMENT, SYSTEM_SETTINGS))
        );
    }

    public CommonRes<SysConfig> setConfig(String key, String value) {
        if (key.startsWith("__")) {
            return CommonRes.failed("can not setup system internal properties :" + key);
        }
        SysConfig sysConfig = sysConfigMapper.selectOne(
                new QueryWrapper<SysConfig>()
                        .eq(SysConfig.CONFIG_KEY, key)
        );
        if (sysConfig == null) {
            sysConfig = new SysConfig();
        }
        sysConfig.setConfigKey(key);
        sysConfig.setConfigValue(value);
        sysConfig.setConfigComment(SYSTEM_SETTINGS);
        if (sysConfig.getId() == null) {
            sysConfigMapper.insert(sysConfig);
        } else {
            sysConfigMapper.updateById(sysConfig);
        }
        BroadcastService.triggerEvent(BroadcastService.Topic.CONFIG);
        return CommonRes.success(sysConfig);
    }

    public void reloadConfig() {
        Configs.refreshConfig(sysConfigMapper.selectList(new QueryWrapper<SysConfig>()
                .eq(SysConfig.CONFIG_COMMENT, SYSTEM_SETTINGS))
        );
    }
}
