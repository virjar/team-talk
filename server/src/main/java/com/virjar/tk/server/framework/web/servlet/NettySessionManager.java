package com.virjar.tk.server.framework.web.servlet;

import com.virjar.tk.server.framework.web.core.NettyServletContext;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Leibniz.Hu
 * Created on 2017-08-28 20:59.
 */
@Slf4j
public class NettySessionManager {
    private final NettyServletContext servletContext;
    private final Map<String, NettyHttpSession> sessions = new ConcurrentHashMap<>();
    static final int SESSION_LIFE_SECONDS = 60 * 30;
    static final int SESSION_LIFE_MILLISECONDS = SESSION_LIFE_SECONDS * 1000;
    private static final int SESSION_LIFE_CHECK_INTER = 1000 * 60;

    public NettySessionManager(NettyServletContext servletContext) {
        this.servletContext = servletContext;
        Thread thread = new Thread(new checkInvalidSessions(), "Session-Check");
        thread.setDaemon(true);
        thread.start();
    }

    ServletContext getServletContext() {
        return servletContext;
    }

    void invalidate(HttpSession session) {
        sessions.remove(session.getId());
    }

    public void updateAccessTime(NettyHttpSession session) {
        if (session != null) {
            session.updateAccessTime();
        }
    }

    public boolean checkValid(NettyHttpSession session) {
        return session != null && sessions.get(session.getId()) != null && !session.expire();
    }

    public NettyHttpSession getSession(String id) {
        return id == null ? null : sessions.get(id);
    }

    public NettyHttpSession createSession() {
        String id = createUniqueSessionId();
        NettyHttpSession newSession = new NettyHttpSession(id, this);
        sessions.put(id, newSession);
        return newSession;
    }

    private String createUniqueSessionId() {
        String prefix = String.valueOf(100000 + new Random().nextInt(899999));
        return new StringBuilder().append(System.currentTimeMillis()).reverse().append(prefix).toString();
    }

    public void setOldSession(NettyHttpSession session) {
        if (session != null) {
            session.setNew(false);
        }
    }

    /**
     * 超时的Session无效化，定期执行
     */
    private class checkInvalidSessions implements Runnable {
        @Override
        public void run() {
            log.info("Session Manager expire-checking thread has been started...");
            while (true) {
                try {
                    Thread.sleep(SESSION_LIFE_CHECK_INTER);
                } catch (InterruptedException ignore) {

                }
                for (NettyHttpSession session : sessions.values()) {
                    if (session.expire()) {
                        log.info("Session(ID={}) is invalidated by Session Manager", session.getId());
                        session.invalidate();
                    }
                }
            }
        }
    }
}
