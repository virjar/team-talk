package com.virjar.tk.server.sys;


import com.virjar.tk.server.im.entity.ImUserInfo;
import org.apache.commons.lang3.BooleanUtils;

public class AppContext {
    private static final ThreadLocal<ImUserInfo> userInfoThreadLocal = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> API_USER = new ThreadLocal<>();

    private static final ThreadLocal<LoginRequired> LOGIN_ANNOTATION = new ThreadLocal<>();

    public static ImUserInfo getUser() {
        return userInfoThreadLocal.get();
    }

    public static void setUser(ImUserInfo user) {
        userInfoThreadLocal.set(user);
    }

    public static void markApiUser() {
        API_USER.set(true);
    }

    public static void setLoginAnnotation(LoginRequired loginRequired) {
        LOGIN_ANNOTATION.set(loginRequired);
    }

    public static LoginRequired getLoginAnnotation() {
        return LOGIN_ANNOTATION.get();
    }

    public static boolean isApiUser() {
        Boolean aBoolean = API_USER.get();
        return BooleanUtils.isTrue(aBoolean);
    }

    public static void removeUser() {
        userInfoThreadLocal.remove();
        API_USER.remove();
        LOGIN_ANNOTATION.remove();
    }
}
