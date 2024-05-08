package com.virjar.tk.server.framework.web.bootstrap;

import com.virjar.tk.server.framework.web.core.NettyServletContext;
import com.virjar.tk.server.framework.web.core.SpringbootNettyWebServer;
import io.netty.bootstrap.Bootstrap;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ClassUtils;

import java.net.URL;
import java.net.URLClassLoader;

@Slf4j
public class EmbeddedNettyFactory extends AbstractServletWebServerFactory implements ResourceLoaderAware {
    private static final String SERVER_INFO = "sbnetty";
    private ResourceLoader resourceLoader;

    @Override
    @SneakyThrows
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        ClassLoader parentClassLoader = resourceLoader != null ?
                resourceLoader.getClassLoader() : ClassUtils.getDefaultClassLoader();

        Package nettyPackage = Bootstrap.class.getPackage();
        String title = nettyPackage.getImplementationTitle();
        String version = nettyPackage.getImplementationVersion();
        log.info("Running with " + title + " " + version);

        if (isRegisterDefaultServlet()) {
            log.warn("This container does not support a default servlet");
        }

        NettyServletContext context = new NettyServletContext(getContextPath(),
                new URLClassLoader(new URL[]{}, parentClassLoader), SERVER_INFO);
        for (ServletContextInitializer initializer : initializers) {
            initializer.onStartup(context);
        }
        return new SpringbootNettyWebServer(context);
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }
}
