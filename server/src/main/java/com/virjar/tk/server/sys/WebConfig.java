package com.virjar.tk.server.sys;

import com.virjar.tk.server.sys.service.env.Constants;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import java.util.List;

/**
 * Date: 2021-06-05
 *
 * @author alienhe
 */
@Slf4j
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Resource
    private LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor);
    }

    private static final String docPath = "/" + Constants.docPath;

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController(docPath, docPath + "/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(docPath + "/")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @SuppressWarnings("all")
                    @Override
                    public org.springframework.core.io.Resource resolveResource(HttpServletRequest request,
                                                                                String requestPath,
                                                                                List<? extends org.springframework.core.io.Resource> locations,
                                                                                ResourceResolverChain chain) {
                        return super.resolveResource(request, requestPath += (requestPath.endsWith("/") ? "index.html" : "/index.html")
                                , locations, chain);
                    }
                });
        // 特殊处理文档静态资源规则，因为文档存在多个二级的index页面
        // 但是在spring里面只有root index会走welcome html
        registry.addResourceHandler(docPath + "/**")
                .addResourceLocations("classpath:/static" + docPath + "/")
                .resourceChain(true).addResolver(new PathResourceResolver() {
                    @SuppressWarnings("all")
                    @Override
                    public org.springframework.core.io.Resource resolveResource(HttpServletRequest request, String requestPath,
                                                                                List<? extends org.springframework.core.io.Resource> locations,
                                                                                ResourceResolverChain chain) {
                        org.springframework.core.io.Resource resource = super.resolveResource(request, requestPath, locations, chain);
                        if (resource != null) {
                            return resource;
                        }
                        return super.resolveResource(request, requestPath + "/index.html", locations, chain);
                    }
                });
    }
}
