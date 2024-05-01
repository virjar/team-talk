package com.virjar.tk.server.utils.net;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

public class GlobalAuthentication {
    // 账号代理的账号密码
    private static final ThreadLocal<AuthHolder> threadLocalProxyAuth = new ThreadLocal<>();

    public static void setupAuthenticator() {
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                //return super.getPasswordAuthentication();
                AuthHolder authHolder = threadLocalProxyAuth.get();
                if (authHolder == null) {
                    return super.getPasswordAuthentication();
                }
                return new PasswordAuthentication(authHolder.user, authHolder.pass);
            }
        });
    }

    public static void setProxyAuth(String user, String pass) {
        threadLocalProxyAuth.set(new AuthHolder(user, pass));
    }
}
