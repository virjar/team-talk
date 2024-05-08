package com.virjar.tk.server.framework.web.core;

import com.virjar.tk.server.framework.web.registration.NettyFilterRegistration;
import com.virjar.tk.server.framework.web.registration.NettyServletRegistration;
import com.virjar.tk.server.framework.web.servlet.NettySessionManager;
import com.virjar.tk.server.framework.web.utils.MimeTypeUtil;
import com.virjar.tk.server.framework.web.utils.RequestUrlPatternMapper;
import com.google.common.collect.ImmutableMap;
import jakarta.servlet.*;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * ServletContext实现
 */
@Slf4j
public class NettyServletContext implements ServletContext {
    private final String contextPath; //保证不以“/”结尾
    private final ClassLoader classLoader;
    private final String serverInfo;
    private final RequestUrlPatternMapper servletUrlPatternMapper;
    @Getter
    private final NettySessionManager sessionManager;

    private final Map<String, NettyServletRegistration> servlets = new HashMap<>(); //getServletRegistration()等方法要用，key是ServletName
    private final Map<String, NettyFilterRegistration> filters = new HashMap<>(); //getFilterRegistration()等方法要用，Key是FilterName
    private final Map<String, String> servletMappings = new HashMap<>(); //保存请求路径urlPattern与Servlet名的映射,urlPattern是不带contextPath的
    private final Hashtable<String, Object> attributes = new Hashtable<>();


    public NettyServletContext(String contextPath, ClassLoader classLoader, String serverInfo) {
        if (contextPath.endsWith("/")) {
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }
        this.contextPath = contextPath;
        this.classLoader = classLoader;
        this.serverInfo = serverInfo;
        this.servletUrlPatternMapper = new RequestUrlPatternMapper(contextPath);
        this.sessionManager = new NettySessionManager(this);
    }

    public void addServletMapping(String urlPattern, String name, Servlet servlet) throws ServletException {
        servletMappings.put(urlPattern, checkNotNull(name));
        servletUrlPatternMapper.addServlet(urlPattern, servlet, name);
    }

    public void addFilterMapping(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String urlPattern) {
        //TODO 过滤器的urlPatter解析
    }

    /**
     * SpringBoot只有一个Context，我觉得直接返回this就可以了
     */
    @Override
    public ServletContext getContext(String uripath) {
        return this;
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public int getMajorVersion() {
        return 3;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 3;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 0;
    }

    @Override
    public String getMimeType(String file) {
        return MimeTypeUtil.getMimeTypeByFileName(file);
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        Set<String> thePaths = new HashSet<>();
        if (!path.endsWith("/")) {
            path += "/";
        }
        String basePath = getRealPath(path);
        if (basePath == null) {
            return thePaths;
        }
        File theBaseDir = new File(basePath);
        if (!theBaseDir.exists() || !theBaseDir.isDirectory()) {
            return thePaths;
        }
        String[] theFiles = theBaseDir.list();
        if (theFiles == null) {
            return thePaths;
        }
        for (String filename : theFiles) {
            File testFile = new File(basePath + File.separator + filename);
            if (testFile.isFile())
                thePaths.add(path + filename);
            else if (testFile.isDirectory())
                thePaths.add(path + filename + "/");
        }
        return thePaths;
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        if (!path.startsWith("/"))
            throw new MalformedURLException("Path '" + path + "' does not start with '/'");
        URL url = new URL(getClassLoader().getResource(""), path.substring(1));
        try {
            IOUtils.closeQuietly(url.openStream());
        } catch (FileNotFoundException e) {
            url = new URL(getClassLoader().getResource(""), "static/" + path.substring(1));
            try {
                IOUtils.closeQuietly(url.openStream());
            } catch (FileNotFoundException fe) {
                //ignore
                url = null;
            } catch (IOException e1) {
                log.error("Throwing exception when getting InputStream of " + path + " in /static", e1);
                url = null;
            }
        } catch (Throwable t) {
            log.error("Throwing exception when getting InputStream of " + path + " in /", t);
            url = null;
        }
        return url;
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        try {
            URL url = getResource(path);
            if (url == null) {
                return null;
            }
            return url.openStream();
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        String servletName = servletUrlPatternMapper.getServletNameByRequestURI(path);
        Servlet servlet;
        try {
            servlet = null == servletName ? null : servlets.get(servletName).getServlet(true);
            if (servlet == null) {
                return null;
            }
            //TODO 过滤器的urlPatter解析
            List<Filter> allNeedFilters = new ArrayList<>();
            for (NettyFilterRegistration registration : this.filters.values()) {
                allNeedFilters.add(registration.getFilter());
            }
            FilterChain filterChain = new NettyFilterChain(servlet, allNeedFilters);
            return new NettyRequestDispatcher(filterChain, path);
        } catch (ServletException e) {
            log.error("Throwing exception when getting Filter from NettyFilterRegistration of path " + path, e);
            return null;
        }
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name) {
        return null;
    }

    @Override
    public Servlet getServlet(String name) throws ServletException {
        return servlets.get(name).getServlet(true);
    }

    @Override
    public Enumeration<Servlet> getServlets() {
        return Collections.emptyEnumeration();
    }

    @Override
    public Enumeration<String> getServletNames() {
        return Collections.emptyEnumeration();
    }

    @Override
    public void log(String msg) {
        log.info(msg);
    }

    @Override
    public void log(Exception exception, String msg) {
        log.error(msg, exception);
    }

    @Override
    public void log(String message, Throwable throwable) {
        log.error(message, throwable);
    }

    @Override
    public String getRealPath(String path) {
        if (!path.startsWith("/"))
            return null;
        try {
            File f = new File(getResource(path).toURI());
            return f.getAbsolutePath();
        } catch (Throwable t) {
            log.error("Throwing exception when getting real path of " + path, t);
            return null;
        }
    }

    @Override
    public String getServerInfo() {
        return serverInfo;
    }

    @Override
    public String getInitParameter(String name) {
        return null;
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.emptyEnumeration();
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        return false;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return attributes.keys();
    }

    @Override
    public void setAttribute(String name, Object object) {
        attributes.put(name, object);
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public String getServletContextName() {
        return getContextPath().toUpperCase(Locale.ENGLISH);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        return addServlet(servletName, className, null);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        return addServlet(servletName, servlet.getClass().getName(), servlet);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        return addServlet(servletName, servletClass.getName());
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        return null;
    }

    private ServletRegistration.Dynamic addServlet(String servletName, String className, Servlet servlet) {
        NettyServletRegistration servletRegistration = new NettyServletRegistration(this, servletName, className, servlet);
        servlets.put(servletName, servletRegistration);
        return servletRegistration;
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> c) throws ServletException {
        try {
            return c.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            log.error("Throwing exception when creating instance of " + c.getName(), e);
        }
        return null;
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        return null;
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return null;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        return addFilter(filterName, className, null);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        return addFilter(filterName, filter.getClass().getName(), filter);
    }

    private FilterRegistration.Dynamic addFilter(String filterName, String className, Filter filter) {
        NettyFilterRegistration filterRegistration = new NettyFilterRegistration(this, filterName, className, filter);
        filters.put(filterName, filterRegistration);
        return filterRegistration;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        return addFilter(filterName, filterClass.getName());
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> c) throws ServletException {
        try {
            return c.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        return filters.get(filterName);
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return ImmutableMap.copyOf(filters);
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        return null;
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) throws IllegalStateException, IllegalArgumentException {

    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return null;
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return null;
    }


    //TODO 暂不支持Listener，现在很少用了吧
    @Override
    public void addListener(String className) {

    }

    @Override
    public <T extends EventListener> void addListener(T t) {

    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {

    }

    @Override
    public <T extends EventListener> T createListener(Class<T> c) throws ServletException {
        return null;
    }

    @Override
    public void declareRoles(String... roleNames) {

    }

    @Override
    public String getVirtualServerName() {
        return null;
    }

    @Override
    public int getSessionTimeout() {
        return 0;
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {

    }

    @Override
    public String getRequestCharacterEncoding() {
        return "";
    }

    @Override
    public void setRequestCharacterEncoding(String encoding) {

    }

    @Override
    public String getResponseCharacterEncoding() {
        return "";
    }

    @Override
    public void setResponseCharacterEncoding(String encoding) {

    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return null;
    }



}
