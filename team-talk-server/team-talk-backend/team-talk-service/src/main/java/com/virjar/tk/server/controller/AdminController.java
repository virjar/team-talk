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
@RequestMapping(Constants.RESTFULL_API_PREFIX + "/admin-op")
public class AdminController {
    @Resource
    private UserInfoService userInfoService;

    @Resource
    private UserInfoMapper userInfoMapper;

    @Resource
    private ConfigService configService;

    @Resource
    private ServerNodeMapper serverNodeMapper;

    @Resource
    private SysLogMapper sysLogMapper;


    @ApiOperation(value = "(管理员专用)创建用户")
    @LoginRequired(forAdmin = true, alert = true)
    @GetMapping("/createUser")
    public CommonRes<UserInfo> createUser(String userName, String password) {
        return userInfoService.register(userName, password);
    }

    @ApiOperation(value = "将一个用户升级为管理员")
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

    @ApiOperation(value = "系统设置项配置模版")
    @GetMapping("/settingTemplate")
    @LoginRequired(forAdmin = true)
    public CommonRes<?> settingTemplate() {
        return CommonRes.success(Settings.allSettingsVo());
    }

    @ApiOperation(value = "所有的系统配置")
    @GetMapping("/allConfig")
    @LoginRequired(forAdmin = true)
    public CommonRes<List<SysConfig>> allConfig() {
        return configService.allConfig();
    }

    @ApiOperation(value = "修改系统配置,批量")
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


    @ApiOperation(value = "修改系统配置")
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


    @ApiOperation(value = "(管理员专用)管理员穿越到普通用户，获取普通用户token")
    @LoginRequired(forAdmin = true)
    @GetMapping("/travelToUser")
    public CommonRes<UserInfo> travelToUser(Long id) {
        UserInfo toUser = userInfoMapper.selectById(id);
        if (toUser == null) {
            return CommonRes.failed("user not exist");
        }
        toUser.setLoginToken(userInfoService.genLoginToken(toUser, LocalDateTime.now()));
        return CommonRes.success(toUser);
    }

    @ApiOperation(value = "(管理员专用)用户列表")
    @LoginRequired(forAdmin = true)
    @GetMapping("/listUser")
    public CommonRes<IPage<UserInfo>> listUser(int page, int pageSize) {
        if (page < 1) {
            page = 1;
        }
        return CommonRes.success(userInfoMapper.selectPage(new Page<>(page, pageSize), new QueryWrapper<>()));

    }

    @ApiOperation(value = "列出 server")
    @LoginRequired(forAdmin = true)
    @GetMapping("/listServer")
    public CommonRes<IPage<ServerNode>> listServer(int page, int pageSize) {
        if (page < 1) {
            page = 1;
        }
        QueryWrapper<ServerNode> queryWrapper = new QueryWrapper<ServerNode>()
                .orderByDesc(ServerNode.LAST_ACTIVE_TIME);
        return CommonRes.success(serverNodeMapper.selectPage(new Page<>(page, pageSize), queryWrapper));
    }


    @ApiOperation(value = "设置服务器启用状态")
    @GetMapping("setServerStatus")
    @LoginRequired(forAdmin = true, alert = true)
    public CommonRes<ServerNode> setServerStatus(Long id, Boolean enable) {
        return CommonRes.ofPresent(serverNodeMapper.selectById(id))
                .ifOk(serverNode -> {
                    serverNode.setEnable(enable);
                    serverNodeMapper.updateById(serverNode);
                });
    }

    @ApiOperation(value = "查询操作日志")
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
