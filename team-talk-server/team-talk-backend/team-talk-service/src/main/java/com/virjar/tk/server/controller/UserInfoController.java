package com.virjar.tk.server.controller;


import com.virjar.tk.server.entity.CommonRes;
import com.virjar.tk.server.entity.UserInfo;
import com.virjar.tk.server.mapper.UserInfoMapper;
import com.virjar.tk.server.service.base.BroadcastService;
import com.virjar.tk.server.service.base.UserInfoService;
import com.virjar.tk.server.service.base.config.Settings;
import com.virjar.tk.server.service.base.env.Constants;
import com.virjar.tk.server.service.base.env.Environment;
import com.virjar.tk.server.service.base.perm.PermsService;
import com.virjar.tk.server.system.AppContext;
import com.virjar.tk.server.system.LoginRequired;
import com.virjar.tk.server.utils.CommonUtils;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import io.swagger.annotations.ApiOperation;
import lombok.SneakyThrows;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

/**
 * <p>
 * 用户中心的核心接口，关于用户登录注册、权限控制、用户profile管理等
 * </p>
 *
 * @author iinti
 * @since 2022-02-22
 */
@RestController
@RequestMapping(Constants.RESTFULL_API_PREFIX + "/user-info")
public class UserInfoController {
    @Resource
    private UserInfoService userInfoService;

    @Resource
    private UserInfoMapper userInfoMapper;

    @Resource
    private PermsService permsService;

    @ApiOperation(value = "登陆")
    @RequestMapping(value = "/login", method = RequestMethod.POST)
    public CommonRes<UserInfo> login(String userName, String password) {
        return userInfoService.login(userName, password);
    }

    @ApiOperation(value = "登陆")
    @RequestMapping(value = "/getLogin", method = RequestMethod.GET)
    public CommonRes<UserInfo> getLogin(String userName, String password) {
        return userInfoService.login(userName, password);
    }

    @ApiOperation(value = "通过一个确定url的登录，登录成功后重定向到根")
    @RequestMapping(value = "/cookieLogin", method = RequestMethod.GET)
    @SneakyThrows
    public void cookieLogin(String userName, String password, HttpServletResponse httpServletResponse) {
        CommonRes<UserInfo> commonRes = userInfoService.login(userName, password);
        if (commonRes.isOk()) {
            Cookie cookie = new Cookie(Constants.userLoginTokenKey, commonRes.getData().getLoginToken());
            // cookie是一个临时的存储，我们只给他60s的有效时间
            cookie.setMaxAge(60);
            httpServletResponse.addCookie(cookie);
            httpServletResponse.sendRedirect("/");
            return;
        }
        CommonUtils.writeRes(httpServletResponse, commonRes);
    }

    @ApiOperation(value = "注册")
    @RequestMapping(value = "/register", method = RequestMethod.POST)
    public CommonRes<UserInfo> register(String userName, String password) {
        return userInfoService.register(userName, password);
    }

    @LoginRequired(apiToken = true)
    @ApiOperation(value = "当前用户信息")
    @RequestMapping(value = "/userInfo", method = RequestMethod.GET)
    public CommonRes<UserInfo> userInfo() {
        UserInfo user = AppContext.getUser();
        if (AppContext.isApiUser()) {
            // api方式无法获取到用户密码，我们认为API是用在代码中，他是低优账户体系。后台账户将会对他保密
            user.setPassword(null);
        }
        return CommonRes.success(user);
    }

    @LoginRequired
    @ApiOperation(value = "刷新当前用户token")
    @GetMapping(value = "/refreshToken")
    public CommonRes<String> refreshToken() {
        String newToken = userInfoService.refreshToken(AppContext.getUser().getLoginToken());
        if (newToken == null) {
            return CommonRes.failed(CommonRes.statusLoginExpire, "请重新登陆");
        }
        return CommonRes.success(newToken);
    }

    @LoginRequired
    @ApiOperation(value = "重置密码")
    @PostMapping(value = "/resetPassword")
    public CommonRes<UserInfo> resetPassword(String newPassword) {
        UserInfo mUser = AppContext.getUser();
        if (mUser.getIsAdmin() && Environment.isDemoSite) {
            return CommonRes.failed("测试demo网站不允许修改管理员密码");
        }
        return userInfoService.resetUserPassword(mUser.getId(), newPassword);
    }

    @LoginRequired
    @ApiOperation(value = "重新生产api访问的token")
    @GetMapping("/regenerateAPIToken")
    public CommonRes<UserInfo> regenerateAPIToken() {
        UserInfo mUser = AppContext.getUser();
        mUser.setApiToken(UUID.randomUUID().toString());
        userInfoMapper.update(null, new UpdateWrapper<UserInfo>()
                .eq(UserInfo.USER_NAME, mUser.getUserName())
                .set(UserInfo.API_TOKEN, mUser.getApiToken())
        );
        BroadcastService.triggerEvent(BroadcastService.Topic.USER);
        return CommonRes.success(mUser);
    }

    @ApiOperation(value = "(管理员专用)给用户编辑权限")
    @LoginRequired(forAdmin = true)
    @PostMapping("/editUserPerm")
    public CommonRes<UserInfo> editUserPerm(@NotBlank String userName, String permsConfig) {
        return userInfoService.editUserPerm(userName, permsConfig);
    }

    @ApiOperation(value = "(管理员专用)所有的权限类型")
    @LoginRequired(forAdmin = true)
    @GetMapping("/permScopes")
    public CommonRes<List<String>> permScopes() {
        return CommonRes.success(permsService.permissionScopes());
    }

    @ApiOperation(value = "(管理员专用)某个作用域下，权限授权范围枚举项")
    @LoginRequired(forAdmin = true)
    @GetMapping("/permItemsOfScope")
    public CommonRes<List<String>> permItemsOfScope(String scope) {
        return permsService.perms(scope);
    }

}
