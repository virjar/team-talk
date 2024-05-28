package com.virjar.tk.server.sys.controller;

import com.alibaba.fastjson.JSONObject;
import com.virjar.tk.server.common.BusinessException;
import com.virjar.tk.server.common.CommonRes;
import com.virjar.tk.server.common.PageResult;
import com.virjar.tk.server.im.entity.ImUserInfo;
import com.virjar.tk.server.im.mapper.ImUserInfoMapper;
import com.virjar.tk.server.im.service.UserInfoService;
import com.virjar.tk.server.sys.LoginRequired;
import com.virjar.tk.server.sys.entity.SysConfig;
import com.virjar.tk.server.sys.entity.SysLog;
import com.virjar.tk.server.sys.entity.SysServerNode;
import com.virjar.tk.server.sys.mapper.SysLogMapper;
import com.virjar.tk.server.sys.mapper.SysServerNodeMapper;
import com.virjar.tk.server.sys.service.config.ConfigService;
import com.virjar.tk.server.sys.service.config.Settings;
import com.virjar.tk.server.sys.service.config.SettingsValidate;
import com.virjar.tk.server.sys.service.env.Constants;
import com.virjar.tk.server.sys.service.env.Environment;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.virjar.tk.server.common.BusinessException.SYSTEM.CANNOT_CHANGE_SETTING_FOR_DEMO_SITE;
import static com.virjar.tk.server.common.BusinessException.SYSTEM.RECORD_NOT_FOUND;
import static com.virjar.tk.server.common.BusinessException.USER.CAN_NOT_SETUP_ADMIN_FOR_DEMO_SITE;
import static com.virjar.tk.server.common.BusinessException.USER.USER_NOT_EXIST;

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

    @Resource
    private R2dbcEntityTemplate r2dbcEntityTemplate;

    @Operation(summary = "(管理员专用)创建用户")
    @LoginRequired(forAdmin = true, alert = true)
    @GetMapping("/createUser")
    public Mono<CommonRes<ImUserInfo>> createUser(String userName, String password) {
        Mono<ImUserInfo> userInfoMono = userInfoService.register(userName, password, true);
        return CommonRes.fromMono(userInfoMono);
    }

    @Operation(summary = "将一个用户升级为管理员")
    @LoginRequired(forAdmin = true, alert = true)
    @GetMapping("/grantAdmin")
    public Mono<CommonRes<ImUserInfo>> grantAdmin(String userName, boolean isAdmin) {
        if (StringUtils.isBlank(userName)) {
            return USER_NOT_EXIST.m();
        }
        if (isAdmin && Environment.isDemoSite) {
            return CAN_NOT_SETUP_ADMIN_FOR_DEMO_SITE.m();
        }
        // todo assert operate you self
        Mono<ImUserInfo> mono = userInfoService.grantAdmin(userName, isAdmin);
        return CommonRes.fromMono(mono);
    }

    @Operation(summary = "系统设置项配置模版")
    @GetMapping("/settingTemplate")
    @LoginRequired(forAdmin = true)
    public CommonRes<JSONObject> settingTemplate() {
        return CommonRes.success(Settings.allSettingsVo());
    }

    @Operation(summary = "所有的系统配置")
    @GetMapping("/allConfig")
    @LoginRequired(forAdmin = true)
    public Mono<CommonRes<List<SysConfig>>> allConfig() {
        return CommonRes.fromMono(
                configService.allConfig()
        );
    }

    @Operation(summary = "修改系统配置,批量")
    @PostMapping("/setConfigs")
    @LoginRequired(forAdmin = true, alert = true)
    public Mono<CommonRes<List<SysConfig>>> setConfigs(@RequestBody Map<String, String> configs) {
        return CommonRes.fromMono(
                configService.setConfigs(configs)
        );
    }


    @Operation(summary = "修改系统配置")
    @PostMapping("/setConfig")
    @LoginRequired(forAdmin = true, alert = true)
    public Mono<SysConfig> setConfig(@RequestBody Map<String, String> data) {
        if (Environment.isDemoSite) {
            return CANNOT_CHANGE_SETTING_FOR_DEMO_SITE.m();
        }
        String key = data.get("key");
        String value = data.get("value");
        String msg = SettingsValidate.doValidate(key, value);
        if (StringUtils.isNotBlank(msg)) {
            return BusinessException.errorM(msg);
        }
        return configService.setConfig(key, value);
    }


    @Operation(summary = "(管理员专用)管理员穿越到普通用户，获取普通用户token")
    @LoginRequired(forAdmin = true)
    @GetMapping("/travelToUser")
    public Mono<CommonRes<ImUserInfo>> travelToUser(Long id) {
        Mono<ImUserInfo> userInfoMono = userInfoMapper.findById(id)
                .switchIfEmpty(USER_NOT_EXIST.m())
                .doOnSuccess((user) ->
                        userInfoService.fillLoginToken(user, LocalDateTime.now())
                );
        return CommonRes.fromMono(userInfoMono);
    }

    @Operation(summary = "(管理员专用)用户列表")
    @LoginRequired(forAdmin = true)
    @GetMapping("/listUser")
    public Mono<CommonRes<PageResult<ImUserInfo>>> listUser(int page, int pageSize, String key) {
        if (page < 1) {
            page = 1;
        }
        Criteria criteria = Criteria.empty();

        if (StringUtils.isNotBlank(key)) {
            criteria = criteria.and(ImUserInfo.USER_NAME).like(key);
        }
        Mono<PageResult<ImUserInfo>> mono = PageResult.selectPag(
                page, pageSize, criteria, ImUserInfo.class, r2dbcEntityTemplate
        );

        return CommonRes.fromMono(mono);
    }

    @Operation(summary = "列出 server")
    @LoginRequired(forAdmin = true)
    @GetMapping("/listServer")
    public Mono<CommonRes<List<SysServerNode>>> listServer() {
        Mono<List<SysServerNode>> mono = serverNodeMapper
                .findAll(Sort.by(Sort.Direction.DESC, SysServerNode.LAST_ACTIVE_TIME))
                .collectList();
        return CommonRes.fromMono(mono);
    }


    @Operation(summary = "设置服务器启用状态")
    @GetMapping("setServerStatus")
    @LoginRequired(forAdmin = true, alert = true)
    public Mono<CommonRes<SysServerNode>> setServerStatus(Long id, Boolean enable) {
        Mono<SysServerNode> mono = serverNodeMapper.findById(id)
                .switchIfEmpty(RECORD_NOT_FOUND.m())
                .flatMap((node) -> {
                    node.setEnable(enable);
                    return serverNodeMapper.save(node);
                });
        return CommonRes.fromMono(mono);
    }

    @Operation(summary = "查询操作日志")
    @GetMapping("/listSystemLog")
    @LoginRequired(forAdmin = true)
    public Mono<CommonRes<PageResult<SysLog>>> listSystemLog(String username, String operation,
                                                             int page, int pageSize) {
        if (page < 1) {
            page = 1;
        }
        Criteria criteria = Criteria.empty();

        if (StringUtils.isNotBlank(username)) {
            criteria = criteria.and(SysLog.USERNAME).is(username);
        }
        if (StringUtils.isNotBlank(operation)) {
            criteria = criteria.and(SysLog.OPERATION).is(operation);
        }
        Query query = Query.query(criteria).sort(Sort.by(Sort.Direction.DESC, SysLog.ID));
        Mono<PageResult<SysLog>> mono = PageResult.selectPag(page, pageSize, query, SysLog.class, r2dbcEntityTemplate);
        return CommonRes.fromMono(mono);
    }
}
