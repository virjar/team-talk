package com.virjar.tk.server.controller;

import com.virjar.tk.server.entity.*;
import com.virjar.tk.server.mapper.ServerNodeMapper;
import com.virjar.tk.server.mapper.SysLogMapper;
import com.virjar.tk.server.mapper.UserInfoMapper;
import com.virjar.tk.server.service.base.BroadcastService;
import com.virjar.tk.server.service.base.UserInfoService;
import com.virjar.tk.server.service.base.config.ConfigService;
import com.virjar.tk.server.service.base.config.Settings;
import com.virjar.tk.server.service.base.config.SettingsValidate;
import com.virjar.tk.server.service.base.env.Constants;
import com.virjar.tk.server.service.base.env.Environment;
import com.virjar.tk.server.system.LoginRequired;
import com.virjar.tk.server.utils.ServerIdentifier;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping(Constants.RESTFULL_API_PREFIX + "/system")
public class SystemController {

    // todo，开源后需要考虑安全，此接口需要做内部鉴权
    @ApiOperation(value = "内部接口,获取当前设备的clientId")
    @GetMapping("/exchangeClientId")
    public CommonRes<String> exchangeClientId() {
        return CommonRes.success(ServerIdentifier.id());
    }

    // todo，开源后需要考虑安全，此接口需要做内部鉴权
    @ApiOperation(value = "内部接口，触发广播")
    @GetMapping("/triggerBroadcast")
    public CommonRes<String> triggerBroadcast(String topic) {
        return CommonRes.success(BroadcastService.callListener(topic));
    }


    @ApiOperation(value = "系统信息")
    @GetMapping("/systemInfo")
    public CommonRes<JSONObject> systemInfo() {
        return Environment.buildInfo();
    }

    @ApiOperation(value = "停机通知（软件更新或者升级前，通知业务模块做收尾工作）,返回当前pending任务数量，当数据为0则代表可以安全停机")
    @GetMapping("/prepareShutdown")
    public Integer prepareShutdown() {
        return Environment.prepareShutdown();
    }

    @ApiOperation(value = "系统通告信息")
    @GetMapping("/systemNotice")
    public CommonRes<String> systemNotice() {
        return CommonRes.success(Settings.systemNotice.value);
    }

    @ApiOperation(value = "文档首页通告信息")
    @GetMapping("/docNotice")
    public CommonRes<String> docNotice() {
        return CommonRes.success(Settings.docNotice.value);
    }
}
