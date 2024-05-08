package com.virjar.tk.server;


import com.baomidou.mybatisplus.generator.AutoGenerator;
import com.baomidou.mybatisplus.generator.config.*;
import com.baomidou.mybatisplus.generator.function.ConverterFileName;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Properties;

/**
 * 此工具用于自动根据数据库生成代码，底层包装了： mybatis-plus-generator
 */
public class CodeGenerator {
    public static void main(String[] args) throws Exception {
        //String fileLocation = CodeGenerator.class.getProtectionDomain().getCodeSource().getLocation().getFile();

        File genOutDirRoot = new File(System.getProperty("user.dir"), "server-backend/src/main/");
        File appConfigFile = new File(genOutDirRoot, "resources/application.properties");
        Properties properties = new Properties();
        properties.load(Files.newInputStream(appConfigFile.toPath()));
        DataSourceConfig dsc = new DataSourceConfig.Builder(
                properties.getProperty("spring.datasource.url"),
                properties.getProperty("spring.datasource.username"),
                properties.getProperty("spring.datasource.password")
        ).build();

        String currentModule = "im";

        PackageConfig pc = new PackageConfig.Builder()
                .parent("com.virjar.tk.server")
                .moduleName(currentModule)
                .pathInfo(new HashMap<>() {{
                    // 默认xml生成在java代码目录，我们把他重定向到资源目录
                    String path = genOutDirRoot + "/resources/mapper/" + currentModule;
                    put(OutputFile.xml, path);
                }})
                .build();
        ConverterFileName DISABLE_GEN = entityName -> "";

        StrategyConfig strategy = new StrategyConfig.Builder()
                .addInclude(currentModule + "_.+")
                .entityBuilder().enableColumnConstant().enableLombok()
                .controllerBuilder()// disable gen Controller
                .convertFileName(DISABLE_GEN)
                .serviceBuilder() // disable gen Service
                .convertServiceFileName(DISABLE_GEN)
                .convertServiceImplFileName(DISABLE_GEN)
                .build();
        GlobalConfig gc = new GlobalConfig.Builder()
                .enableSpringdoc()
                .disableServiceInterface()
                .outputDir(genOutDirRoot + "/java")
                .author(System.getProperty("user.name"))
                .disableOpenDir()
                .build();
        AutoGenerator mpg = new AutoGenerator(dsc)
                .strategy(strategy)
                .global(gc)
                .packageInfo(pc);
        mpg.execute();
    }
}
