package com.virjar.tk.server;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.virjar.tk.server.sys.service.BroadcastService;
import com.virjar.tk.server.sys.service.config.ConfigService;
import com.virjar.tk.server.sys.service.env.Constants;
import com.virjar.tk.server.sys.service.env.Environment;
import com.virjar.tk.server.sys.service.safethread.Looper;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Getter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Nonnull;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

@SpringBootApplication
@EnableAspectJAutoProxy
@EnableScheduling
@Configuration
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
    public OpenAPI springShopOpenAPI() {
        return new OpenAPI()
                .info(new Info().title("teamTalk")
                        .description("一个用于企业办公的沟通软件")
                        .version("v0.0.1")
                        .license(new License().name("Apache 2.0").url("https://tk.virjar.com")))
                .externalDocs(new ExternalDocumentation()
                        .description("TeamTalk Wiki Documentation")
                        .url("https://team-talk.virjar.com/team-talk-docs"));
    }

    @Bean
    public GroupedOpenApi createRestApi() {
        return GroupedOpenApi.builder()
                .group("System")
                .displayName("系统级别API")
                .pathsToMatch(Constants.RESTFULL_API_PREFIX + "/system/**")
                .addOpenApiCustomizer(new OpenApiCustomizer() {
                    @Override
                    public void customise(OpenAPI openApi) {
                        // todo
                    }
                })
                .build();
    }

    @Override
    public void onApplicationEvent(@Nonnull WebServerInitializedEvent event) {
        Environment.setupApp(event);
    }

    public static void main(String[] args) throws IOException {
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
                if (absolutePath.endsWith("target/classes") || absolutePath.endsWith("build/resources/main") || absolutePath.endsWith("conf")) {
                    argList.add("--LogbackDir=" + new File(classPathDir.getParentFile(), "logs").getAbsolutePath());
                    hint = true;
                }
            }
            if (!hint) {
                argList.add("--LogbackDir=" + new File(".int/logs").getAbsolutePath());
            }
        }

        //减少对用户的打扰，各组件大概率不会需要配置的参数，直接硬编码到源码中
        springContextParamSetup(argList);

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

    private static void springContextParamSetup(List<String> argList) {
        // setup metric config
        checkAddPram(argList, "spring.application.name", Constants.appName);
        checkAddPram(argList, "management.endpoints.web.exposure.include", "*");
        checkAddPram(argList, "management.endpoints.web.base-path", Constants.RESTFULL_API_PREFIX + "/actuator");
        checkAddPram(argList, "management.metrics.tags.application", Constants.appName);


        // setup compression
        checkAddPram(argList, "server.compression.enabled", "true");
        checkAddPram(argList, "server.compression.mime-types",
                "application/json,application/xml,text/html,text/xml,text/plain,application/javascript,text/css"
        );
        checkAddPram(argList, "server.compression.min-response-size", "10");
        checkAddPram(argList, "server.compression.excluded-user-agents", "gozilla,traviata");

        // setup jackson
        checkAddPram(argList, "spring.jackson.date-format", "yyyy-MM-dd HH:mm:ss");
        checkAddPram(argList, "spring.jackson.time-zone", "GMT+8");
    }

    private static void checkAddPram(List<String> argList, String key, String value) {
        for (String exist : argList) {
            if (StringUtils.containsIgnoreCase(exist, key)) {
                return;
            }
        }
        argList.add("--" + key + "=" + value);
    }
}
