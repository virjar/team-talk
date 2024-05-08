package com.virjar.tk.server.framework.web.registration;

import com.virjar.tk.server.framework.web.core.NettyServletContext;
import jakarta.servlet.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Servlet的注册器，一个Servlet对应一个注册器
 */
@Slf4j
public class NettyServletRegistration extends AbstractNettyRegistration implements ServletRegistration.Dynamic {
    private final AtomicBoolean initialised = new AtomicBoolean(false);
    private volatile Servlet servlet;
    private final Collection<String> urlPatternMappings = new LinkedList<>();

    public NettyServletRegistration(NettyServletContext context, String servletName, String className, Servlet servlet) {
        super(servletName, className, context);
        this.servlet = servlet;
    }

    public Servlet getServlet(boolean ensureInitialized) throws ServletException {
        if (servlet == null) {
            synchronized (this) {
                if (servlet == null) {
                    try {
                        servlet = (Servlet) Class.forName(getClassName()).newInstance(); //反射获取实例
                    } catch (Exception e) {
                        throw new ServletException(e);
                    }
                }
            }
        }
        if (ensureInitialized) {
            if (initialised.compareAndSet(false, true)) {
                servlet.init(this); //初始化Servlet
            }
        }
        return servlet;
    }

    @Override
    public void setLoadOnStartup(int loadOnStartup) {

    }

    @Override
    public Set<String> setServletSecurity(ServletSecurityElement constraint) {
        return null;
    }

    @Override
    public void setMultipartConfig(MultipartConfigElement multipartConfig) {

    }

    @Override
    public void setRunAsRole(String roleName) {

    }

    @Override
    public String getRunAsRole() {
        return null;
    }

    @Override
    public Set<String> addMapping(String... urlPatterns) {
        //在RequestUrlPatternMapper中会检查url Pattern是否冲突
        NettyServletContext context = getNettyContext();
        for (String urlPattern : urlPatterns) {
            try {
                context.addServletMapping(urlPattern, getName(), getServlet(false));
            } catch (ServletException e) {
                log.error("Throwing exception when getting Servlet in NettyServletRegistration.", e);
            }
        }
        urlPatternMappings.addAll(Arrays.asList(urlPatterns));
        return new HashSet<>(urlPatternMappings);
    }

    @Override
    public Collection<String> getMappings() {
        return urlPatternMappings;
    }
}
