package com.virjar.tk.server.im.controller;


import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.virjar.tk.server.common.CommonRes;
import com.virjar.tk.server.im.entity.ImUserInfo;
import com.virjar.tk.server.im.mapper.ImUserInfoMapper;
import com.virjar.tk.server.im.service.UserInfoService;
import com.virjar.tk.server.sys.AppContext;
import com.virjar.tk.server.sys.LoginRequired;
import com.virjar.tk.server.sys.service.env.Constants;
import com.virjar.tk.server.sys.service.env.Environment;
import com.virjar.tk.server.utils.CommonUtils;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import org.springframework.web.bind.annotation.*;

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
    private ImUserInfoMapper userInfoMapper;

    @Operation(summary = "登陆")
    @RequestMapping(value = "/login", method = RequestMethod.POST)
    public CommonRes<ImUserInfo> login(String userName, String password) {
        return userInfoService.login(userName, password);
    }

    @Operation(summary = "登陆")
    @RequestMapping(value = "/getLogin", method = RequestMethod.GET)
    public CommonRes<ImUserInfo> getLogin(String userName, String password) {
        return userInfoService.login(userName, password);
    }

    @Operation(summary = "通过一个确定url的登录，登录成功后重定向到根")
    @RequestMapping(value = "/cookieLogin", method = RequestMethod.GET)
    @SneakyThrows
    public void cookieLogin(String userName, String password, HttpServletResponse httpServletResponse) {
        CommonRes<ImUserInfo> commonRes = userInfoService.login(userName, password);
        if (commonRes.isOk()) {
            Cookie cookie = new Cookie(Constants.userLoginTokenKey, commonRes.getData().getWebLoginToken());
            // cookie是一个临时的存储，我们只给他60s的有效时间
            cookie.setMaxAge(60);
            httpServletResponse.addCookie(cookie);
            httpServletResponse.sendRedirect("/");
            return;
        }
        CommonUtils.writeRes(httpServletResponse, commonRes);
    }

    @Operation(summary = "注册")
    @RequestMapping(value = "/register", method = RequestMethod.POST)
    public CommonRes<ImUserInfo> register(String userName, String password) {
        return userInfoService.register(userName, password);
    }

    @LoginRequired(apiToken = true)
    @Operation(summary = "当前用户信息")
    @RequestMapping(value = "/userInfo", method = RequestMethod.GET)
    public CommonRes<ImUserInfo> userInfo() {
        ImUserInfo user = AppContext.getUser();
        if (AppContext.isApiUser()) {
            // api方式无法获取到用户密码，我们认为API是用在代码中，他是低优账户体系。后台账户将会对他保密
            user.setPassword(null);
        }
        return CommonRes.success(user);
    }

    @LoginRequired
    @Operation(summary = "刷新当前用户token")
    @GetMapping(value = "/refreshToken")
    public CommonRes<String> refreshToken() {
        String newToken = userInfoService.refreshToken(AppContext.getUser().getWebLoginToken());
        if (newToken == null) {
            return CommonRes.failed(CommonRes.statusLoginExpire, "请重新登陆");
        }
        return CommonRes.success(newToken);
    }

    @LoginRequired
    @Operation(summary = "重置密码")
    @PostMapping(value = "/resetPassword")
    public CommonRes<ImUserInfo> resetPassword(String newPassword) {
        ImUserInfo mUser = AppContext.getUser();
        if (mUser.getSysAdmin() && Environment.isDemoSite) {
            return CommonRes.failed("测试demo网站不允许修改管理员密码");
        }
        return userInfoService.resetUserPassword(mUser.getId(), newPassword);
    }

    @LoginRequired
    @Operation(summary = "重新生产api访问的token")
    @GetMapping("/regenerateAPIToken")
    public CommonRes<ImUserInfo> regenerateAPIToken() {
        ImUserInfo mUser = AppContext.getUser();
        mUser.setApiToken(UUID.randomUUID().toString());
        userInfoMapper.update(null, new UpdateWrapper<ImUserInfo>()
                .eq(ImUserInfo.USER_NAME, mUser.getUserName())
                .set(ImUserInfo.API_TOKEN, mUser.getApiToken())
        );
        return CommonRes.success(mUser);
    }
}
