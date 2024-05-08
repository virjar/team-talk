package com.virjar.tk.server.sys.service.env;

import com.virjar.tk.server.BuildInfo;
import com.virjar.tk.server.common.CommonRes;
import com.virjar.tk.server.sys.service.config.Configs;
import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

@Slf4j
public class Environment {
    private static final Properties buildProperties = new Properties() {
        {
            try {
                InputStream stream = Configs.class.getClassLoader().getResourceAsStream(Constants.BUILD_CONFIG_PROPERTIES);
                if (stream != null) {
                    load(stream);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    };
    public static final int versionCode = BuildInfo.versionCode;
    public static final String versionName = BuildInfo.versionName;
    public static final String buildTime = BuildInfo.buildTime;
    public static final String buildUser = BuildInfo.buildUser;
    public static final String gitId = buildProperties.getProperty("env.gitId", "");

    public static final boolean isLocalDebug =
            BooleanUtils.isTrue(Boolean.valueOf(Configs.getConfig("env.localDebug", "false")));
    public static final boolean isDemoSite =
            BooleanUtils.isTrue(Boolean.valueOf(Configs.getConfig("env.demoSite", "false")));

    public static final int webWorkerThreads =
            Integer.parseInt(Configs.getConfig("env.webWorkerThreads", "30"));


    public static CommonRes<JSONObject> buildInfo() {
        return CommonRes.success(new JSONObject()
                .fluentPut("buildInfo",
                        new JSONObject()
                                .fluentPut("versionCode", versionCode)
                                .fluentPut("versionName", versionName)
                                .fluentPut("buildTime", buildTime)
                                .fluentPut("buildUser", buildUser)
                                .fluentPut("gitId", gitId)
                ).fluentPut("env",
                        new JSONObject()
                                .fluentPut("demoSite", isDemoSite)
                                .fluentPut("debug", isLocalDebug)
                )

        );
    }

    @Getter
    private static ApplicationContext app;

    @Getter
    private static boolean localCodeMode = false;


    public static void setupApp(WebServerInitializedEvent event) {
        app = event.getApplicationContext();
    }

    @SneakyThrows
    public static void upgradeIfNeed(DataSource dataSource) {
        upgradeRuleHolders.sort(Comparator.comparingInt(o -> o.fromVersionCode));
        doDbUpGradeTask(dataSource);

        File versionCodeFile = resolveVersionCodeCtrFile();
        if (versionCodeFile == null) {
            // 本地代码执行模式，认为一定时最新版本，不需要执行升级代码
            localCodeMode = true;
            return;
        }
        doLocalUpGradeTask(versionCodeFile);
        System.out.println("app: " + Constants.appName + " version:(" + versionCode + ":" + versionName + ") buildTime:" + buildTime);
    }

    @SuppressWarnings("all")
    private static final String DB_VERSION_SQL =
            "select config_value from sys_config where config_key='_teamTalk_framework_version' and config_comment='_teamTalk_framework'";

    @SuppressWarnings("all")
    private static final String UPDATE_DB_VERSION_SQL =
            "insert into sys_config (`config_comment`,`config_key`,`config_value`) values ('_teamTalk_framework','_teamTalk_framework_version','" + versionCode + "') " +
                    "on duplicate key update `config_value`='" + versionCode + "'";

    private static void doDbUpGradeTask(DataSource dataSource) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // fetch preVersion
            try (Statement statement = conn.createStatement()) {
                try (ResultSet resultSet = statement.executeQuery(DB_VERSION_SQL)) {
                    if (resultSet.next()) {
                        int preVersionCode = Integer.parseInt(resultSet.getString(1));
                        for (UpgradeRuleHolder upgradeRuleHolder : upgradeRuleHolders) {
                            if (upgradeRuleHolder.fromVersionCode < preVersionCode) {
                                continue;
                            }
                            System.out.println("db upgrade app from: " + upgradeRuleHolder.fromVersionCode + " to: " + upgradeRuleHolder.toVersionCode);
                            upgradeRuleHolder.upgradeHandler.doDbUpgrade(dataSource);
                            preVersionCode = upgradeRuleHolder.toVersionCode;
                        }
                    }
                }
            }

            // flush now version
            try (Statement statement = conn.createStatement()) {
                statement.execute(UPDATE_DB_VERSION_SQL);
            }
        }
    }

    private static void doLocalUpGradeTask(File versionCodeFile) throws IOException {
        if (versionCodeFile.exists()) {
            int preVersionCode = Integer.parseInt(FileUtils.readFileToString(versionCodeFile, StandardCharsets.UTF_8));

            for (UpgradeRuleHolder upgradeRuleHolder : upgradeRuleHolders) {
                if (upgradeRuleHolder.fromVersionCode < preVersionCode) {
                    continue;
                }
                System.out.println("local upgrade app from: " + upgradeRuleHolder.fromVersionCode + " to: " + upgradeRuleHolder.toVersionCode);
                upgradeRuleHolder.upgradeHandler.doLocalUpgrade();
                preVersionCode = upgradeRuleHolder.toVersionCode;
            }
        }


        FileUtils.write(versionCodeFile, String.valueOf(versionCode), StandardCharsets.UTF_8);
    }

    private static File resolveVersionCodeCtrFile() {
        URL configURL = Environment.class.getClassLoader().getResource(Constants.APPLICATION_PROPERTIES);
        if (configURL != null && configURL.getProtocol().equals("file")) {
            File classPathDir = new File(configURL.getFile()).getParentFile();
            String absolutePath = classPathDir.getAbsolutePath();
            if (absolutePath.endsWith("target/classes")// for maven
                    || absolutePath.endsWith("build/resources/main")// for gradle
            ) {
                return null;
            } else if (absolutePath.endsWith("conf")) {
                return new File(classPathDir, "versionCode.txt");
            } else {
                throw new IllegalStateException("can not resolve classpath: " + classPathDir.getAbsolutePath());
            }
        } else {
            throw new IllegalStateException("can not resolve env: " + configURL);
        }
    }

    private static final List<UpgradeRuleHolder> upgradeRuleHolders = new ArrayList<>();

    @SuppressWarnings("all")
    private static void registerUpgradeTask(int fromVersionCode, int toVersionCode, UpgradeHandler upgradeHandler) {
        upgradeRuleHolders.add(new UpgradeRuleHolder(fromVersionCode, toVersionCode, upgradeHandler));
    }

//    static {
//
//        registerUpgradeTask(-1, 1, new SQLExecuteUpgradeHandler("upgrade_v1_v2.sql"));
//    }

    @AllArgsConstructor
    private static class UpgradeRuleHolder {
        private int fromVersionCode;
        private int toVersionCode;
        private UpgradeHandler upgradeHandler;
    }


    public static void registerShutdownHook(Runnable runnable) {
        ShutdownHook.registerShutdownHook(runnable);
    }


    public static int prepareShutdown() {
        return ShutdownHook.prepareShutdown();
    }
}
