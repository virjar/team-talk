package com.virjar.tk.server.sys.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.virjar.tk.server.common.CommonRes;
import com.virjar.tk.server.im.entity.ImUserInfo;
import com.virjar.tk.server.im.mapper.ImUserInfoMapper;
import com.virjar.tk.server.sys.LoginRequired;
import com.virjar.tk.server.sys.mapper.SysServerNodeMapper;
import com.virjar.tk.server.sys.mapper.SysLogMapper;
import com.virjar.tk.server.sys.service.BroadcastService;
import com.virjar.tk.server.im.service.UserInfoService;
import com.virjar.tk.server.sys.service.config.ConfigService;
import com.virjar.tk.server.sys.service.config.Settings;
import com.virjar.tk.server.sys.service.config.SettingsValidate;
import com.virjar.tk.server.sys.service.env.Constants;
import com.virjar.tk.server.sys.service.env.Environment;
import com.virjar.tk.server.sys.entity.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping(Constants.RESTFULL_API_PREFIX + "/admin-op")
public class AdminController {
    @Resource
    private UserInfoService userInfoService;

    @Resource
    private ImUserInfoMapper userInfoMapper;

    @Resource
    private ConfigService configService;

    @Resource
    private SysServerNodeMapper serverNodeMapper;

    @Resource
    private SysLogMapper sysLogMapper;


    @Operation(summary = "(管理员专用)创建用户")
    @LoginRequired(forAdmin = true, alert = true)
    @GetMapping("/createUser")
    public CommonRes<ImUserInfo> createUser(String userName, String password) {
        return userInfoService.register(userName, password);
    }

    @Operation(summary = "将一个用户升级为管理员")
    @LoginRequired(forAdmin = true, alert = true)
    @GetMapping("/grantAdmin")
    public CommonRes<String> grantAdmin(String userName, boolean isAdmin) {
        if (StringUtils.isBlank(userName)) {
            return CommonRes.failed("没有用户名");
        }
        if (isAdmin && Environment.isDemoSite) {
            return CommonRes.failed("测试demo网站不允许设置新的管理员");
        }
        return userInfoService.grantAdmin(userName, isAdmin);
    }

    @Operation(summary ="系统设置项配置模版")
    @GetMapping("/settingTemplate")
    @LoginRequired(forAdmin = true)
    public CommonRes<?> settingTemplate() {
        return CommonRes.success(Settings.allSettingsVo());
    }

    @Operation(summary = "所有的系统配置")
    @GetMapping("/allConfig")
    @LoginRequired(forAdmin = true)
    public CommonRes<List<SysConfig>> allConfig() {
        return configService.allConfig();
    }

    @Operation(summary = "修改系统配置,批量")
    @PostMapping("/setConfigs")
    @LoginRequired(forAdmin = true, alert = true)
    public CommonRes<String> setConfigs(@RequestBody Map<String, String> configs) {
        if (Environment.isDemoSite) {
            return CommonRes.failed("测试demo网站不允许修改配置");
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
            return CommonRes.failed(errorMsg);
        }

        if (configs.isEmpty()) {
            return CommonRes.failed("no config passed");
        }

        for (Map.Entry<String, String> entry : configs.entrySet()) {
            CommonRes<SysConfig> res = configService.setConfig(entry.getKey(), entry.getValue());
            if (!res.isOk()) {
                return res.errorTransfer();
            }
        }
        return CommonRes.success("ok");

    }


    @Operation(summary = "修改系统配置")
    @PostMapping("/setConfig")
    @LoginRequired(forAdmin = true, alert = true)
    public CommonRes<SysConfig> setConfig(@RequestBody Map<String, String> data) {
        if (Environment.isDemoSite) {
            return CommonRes.failed("测试demo网站不允许修改配置");
        }
        String key = data.get("key");
        String value = data.get("value");
        String msg = SettingsValidate.doValidate(key, value);
        if (StringUtils.isNotBlank(msg)) {
            return CommonRes.failed(msg);
        }
        CommonRes<SysConfig> ret = configService.setConfig(key, value);
        BroadcastService.triggerEvent(BroadcastService.Topic.CONFIG);
        return ret;
    }


    @Operation(summary = "(管理员专用)管理员穿越到普通用户，获取普通用户token")
    @LoginRequired(forAdmin = true)
    @GetMapping("/travelToUser")
    public CommonRes<ImUserInfo> travelToUser(Long id) {
        ImUserInfo toUser = userInfoMapper.selectById(id);
        if (toUser == null) {
            return CommonRes.failed("user not exist");
        }
        toUser.setWebLoginToken(userInfoService.genLoginToken(toUser, LocalDateTime.now()));
        return CommonRes.success(toUser);
    }

    @Operation(summary = "(管理员专用)用户列表")
    @LoginRequired(forAdmin = true)
    @GetMapping("/listUser")
    public CommonRes<IPage<ImUserInfo>> listUser(int page, int pageSize) {
        if (page < 1) {
            page = 1;
        }
        return CommonRes.success(userInfoMapper.selectPage(new Page<>(page, pageSize), new QueryWrapper<>()));

    }

    @Operation(summary = "列出 server")
    @LoginRequired(forAdmin = true)
    @GetMapping("/listServer")
    public CommonRes<IPage<SysServerNode>> listServer(int page, int pageSize) {
        if (page < 1) {
            page = 1;
        }
        QueryWrapper<SysServerNode> queryWrapper = new QueryWrapper<SysServerNode>()
                .orderByDesc(SysServerNode.LAST_ACTIVE_TIME);
        return CommonRes.success(serverNodeMapper.selectPage(new Page<>(page, pageSize), queryWrapper));
    }


    @Operation(summary = "设置服务器启用状态")
    @GetMapping("setServerStatus")
    @LoginRequired(forAdmin = true, alert = true)
    public CommonRes<SysServerNode> setServerStatus(Long id, Boolean enable) {
        return CommonRes.ofPresent(serverNodeMapper.selectById(id))
                .ifOk(serverNode -> {
                    serverNode.setEnable(enable);
                    serverNodeMapper.updateById(serverNode);
                });
    }

    @Operation(summary = "查询操作日志")
    @GetMapping("/listSystemLog")
    @LoginRequired(forAdmin = true)
    public CommonRes<IPage<SysLog>> listSystemLog(String username, String operation, int page, int pageSize) {
        QueryWrapper<SysLog> queryWrapper = new QueryWrapper<>();
        if (StringUtils.isNotBlank(username)) {
            queryWrapper.eq(SysLog.USERNAME, username);
        }
        if (StringUtils.isNotBlank(operation)) {
            queryWrapper.eq(SysLog.OPERATION, operation);
        }
        queryWrapper.orderByDesc(SysLog.ID);
        return CommonRes.success(sysLogMapper.selectPage(new Page<>(page, pageSize), queryWrapper));
    }
}
