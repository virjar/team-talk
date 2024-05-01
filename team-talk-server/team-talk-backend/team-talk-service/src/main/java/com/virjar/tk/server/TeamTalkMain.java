package com.virjar.tk.server;

import com.virjar.tk.server.controller.UserInfoController;
import com.virjar.tk.server.service.base.BroadcastService;
import com.virjar.tk.server.service.base.config.ConfigService;
import com.virjar.tk.server.service.base.env.Constants;
import com.virjar.tk.server.service.base.env.Environment;
import com.virjar.tk.server.service.base.safethread.Looper;
import com.baomidou.mybatisplus.extension.plugins.OptimisticLockerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.PaginationInterceptor;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import io.micrometer.core.instrument.util.IOUtils;
import lombok.Getter;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import proguard.annotation.Keep;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

import javax.annotation.Nonnull;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

@SpringBootApplication
@EnableAspectJAutoProxy
@MapperScan("com.virjar.tk.server.mapper")
@EnableSwagger2
@EnableScheduling
@Configuration
@Keep
public class TeamTalkMain implements ApplicationListener<WebServerInitializedEvent> {

    @Getter
    private static final Looper shardThread = new Looper("ShardThread").startLoop();


    @Resource
    private DataSource dataSource;

    @Resource
    private ConfigService configService;

    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void reloadConfig() {
        configService.reloadConfig();
    }

    @PostConstruct
    public void init() {
        // 第一步，在涉及到版本升级的场景下，调用版本管理器，进行升级操作，版本升级操作主要是对数据的表结构进行修改
        try {
            Environment.upgradeIfNeed(dataSource);
        } catch (Throwable throwable) {
            System.out.println("upgrade failed,please contact iint business support");
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
        // 配置加载和刷新，需要放到Main上面，这样配置加载将会在所有bean启动前初始化好，
        // 让业务模块在运行的时候就拿到数据库的配置项
        reloadConfig();
        BroadcastService.register(BroadcastService.Topic.CONFIG, this::reloadConfig);
    }

    @Bean
    public Docket createRestApi() {
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("teamTalk")
                .description("teamTalk系统")
                .version("2.0")
                .build();
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage(UserInfoController.class.getPackage().getName()))
                .paths(PathSelectors.any())
                .build();
    }

    @Bean
    public PaginationInterceptor paginationInterceptor() {
        PaginationInterceptor paginationInterceptor = new PaginationInterceptor();
        paginationInterceptor.setLimit(1000);
        return paginationInterceptor;
    }

    @Bean
    public OptimisticLockerInterceptor optimisticLockerInterceptor() {
        return new OptimisticLockerInterceptor();
    }

    @Override
    public void onApplicationEvent(@Nonnull WebServerInitializedEvent event) {
        Environment.setupApp(event);
    }

    @Keep
    public static void main(String[] args) {
        List<String> argList = Lists.newArrayList(args);
        argList.add("--env.versionCode=" + Environment.versionCode);
        argList.add("--env.versionName=" + Environment.versionName);

        // setup log dir
        boolean hasSetupLogDir = argList.stream().anyMatch(s -> s.contains("--LogbackDir"));
        if (!hasSetupLogDir) {
            boolean hint = false;
            URL configURL = TeamTalkMain.class.getClassLoader().getResource(Constants.APPLICATION_PROPERTIES);
            if (configURL != null && configURL.getProtocol().equals("file")) {
                File classPathDir = new File(configURL.getFile()).getParentFile();
                String absolutePath = classPathDir.getAbsolutePath();
                if (absolutePath.endsWith("target/classes") || absolutePath.endsWith("conf")) {
                    argList.add("--LogbackDir=" + new File(classPathDir.getParentFile(), "logs").getAbsolutePath());
                    hint = true;
                }
            }
            if (!hint) {
                argList.add("--LogbackDir=" + new File(".int/logs").getAbsolutePath());
            }
        }
        // setup metric config
        argList.add("--spring.application.name=" + Constants.appName);
        argList.add("--management.endpoints.web.exposure.include=*");
        argList.add("--management.endpoints.web.base-path=" + Constants.RESTFULL_API_PREFIX + "/actuator");
        argList.add("--management.metrics.tags.application=" + Constants.appName);


        InputStream stream = TeamTalkMain.class.getClassLoader().getResourceAsStream("addition.txt");
        if (stream != null) {
            List<String> strings = Splitter.on(CharMatcher.breakingWhitespace())
                    .omitEmptyStrings()
                    .trimResults()
                    .splitToList(IOUtils.toString(stream, StandardCharsets.UTF_8));

            for (String str : strings) {
                if (!argList.contains(str)) {
                    argList.add(str);
                }
            }
        }
        try {
            SpringApplication.run(TeamTalkMain.class, argList.toArray(new String[]{}));
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            // 如果启动失败，必须退出，否则docker的进程守护无法感知到服务启动失败
            System.exit(1);
        }
    }
}
