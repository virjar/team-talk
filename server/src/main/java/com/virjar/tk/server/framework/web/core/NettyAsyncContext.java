package com.virjar.tk.server.framework.web.core;

import io.netty.channel.ChannelHandlerContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * comment by virjar: 异步的方式存在bug，待定解决
 */
public class NettyAsyncContext implements AsyncContext {
    private ServletRequest servletRequest;
    private final ChannelHandlerContext ctx;
    private ServletResponse servletResponse;
    @Getter
    private boolean asyncStarted;
    private final List<AsyncListener> listeners;

    public NettyAsyncContext(ServletRequest servletRequest, ChannelHandlerContext ctx) {
        this.servletRequest = servletRequest;
        this.ctx = ctx;
        this.listeners = new ArrayList<>();
    }

    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        this.servletRequest = servletRequest;
        this.servletResponse = servletResponse;
        asyncStarted = true;
        return this;
    }

    @Override
    public ServletRequest getRequest() {
        return servletRequest;
    }

    @Override
    public ServletResponse getResponse() {
        return servletResponse;
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        return true;
    }

    @Override
    public void dispatch() {
        if (servletRequest instanceof HttpServletRequest) {
            HttpServletRequest request = (HttpServletRequest) servletRequest;
            String path = request.getServletPath();
            String pathInfo = request.getPathInfo();
            if (null != pathInfo) {
                path += pathInfo;
            }
            dispatch(path);
        }
    }

    @Override
    public void dispatch(String path) {
        dispatch(servletRequest.getServletContext(), path);
    }

    @SneakyThrows
    @Override
    public void dispatch(ServletContext context, String path) {
        if (servletResponse instanceof HttpServletResponse) {
            ((HttpServletResponse) servletResponse)
                    .sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "not support async dispatch now");
        }
    }

    @Override
    @SneakyThrows
    public void complete() {
        IOUtils.closeQuietly(servletResponse.getOutputStream());

    }

    @Override
    public void start(Runnable run) {
        ctx.executor().submit(run, Object.class);
    }

    @Override
    public void addListener(AsyncListener listener) {
        listeners.add(listener);
    }

    @Override
    public void addListener(AsyncListener listener, ServletRequest servletRequest, ServletResponse servletResponse) {

    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> clazz) {
        return null;
    }

    @Override
    public void setTimeout(long timeout) {

    }

    @Override
    public long getTimeout() {
        return 0;
    }
}
