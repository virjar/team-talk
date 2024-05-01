package com.virjar.tk.server;


import com.baomidou.mybatisplus.core.toolkit.StringPool;
import com.baomidou.mybatisplus.generator.AutoGenerator;
import com.baomidou.mybatisplus.generator.InjectionConfig;
import com.baomidou.mybatisplus.generator.config.*;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import com.baomidou.mybatisplus.generator.config.rules.FileType;
import com.baomidou.mybatisplus.generator.config.rules.NamingStrategy;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 此工具用于自动根据数据库生成代码，底层包装了： mybatis-plus-generator
 */
public class CodeGenerator {
    public static void main(String[] args) throws Exception {
        AutoGenerator mpg = new AutoGenerator();
        GlobalConfig gc = new GlobalConfig();
        final String projectPath = System.getProperty("user.dir");
        gc.setOutputDir(projectPath + "/team-talk-service/src/main/java");
        gc.setAuthor(System.getProperty("user.name"));
        gc.setOpen(false);
        mpg.setGlobalConfig(gc);

        File appConfigFile = new File(projectPath, "team-talk-service/src/main/resources-env/dev/application.properties");
        Properties properties = new Properties();
        properties.load(Files.newInputStream(appConfigFile.toPath()));

        DataSourceConfig dsc = new DataSourceConfig();
        dsc.setUrl(properties.getProperty("spring.datasource.url"));
        dsc.setDriverName("com.mysql.cj.jdbc.Driver");
        dsc.setUsername(properties.getProperty("spring.datasource.username"));
        dsc.setPassword(properties.getProperty("spring.datasource.password"));


        mpg.setDataSource(dsc);

        final PackageConfig pc = new PackageConfig();
        pc.setModuleName("");
        pc.setParent("com.virjar.tk.server");
        mpg.setPackageInfo(pc);

        InjectionConfig cfg = new InjectionConfig() {
            @Override
            public void initMap() {
                setFileCreate((configBuilder, fileType, filePath) ->
                        // 禁用 controller、service、serviceImpl的生成，他们不符合我们的代码范式
                        fileType != FileType.CONTROLLER
                                && fileType != FileType.SERVICE
                                && fileType != FileType.SERVICE_IMPL);
            }
        };
        List<FileOutConfig> focList = new ArrayList<>();
        focList.add(new FileOutConfig("/templates/mapper.xml.ftl") {
            @Override
            public String outputFile(TableInfo tableInfo) {
                return projectPath + "/team-talk-service/src/main/resources/mapper/" + pc.getModuleName()
                        + "/" + tableInfo.getEntityName() + "Mapper" + StringPool.DOT_XML;
            }
        });
        cfg.setFileOutConfigList(focList);
        mpg.setCfg(cfg);
        mpg.setTemplate(new TemplateConfig().setXml(null));


        // 策略
        StrategyConfig strategy = new StrategyConfig();
        strategy.setNaming(NamingStrategy.underline_to_camel);
        strategy.setColumnNaming(NamingStrategy.underline_to_camel);
        strategy.setEntityLombokModel(true);
        strategy.setRestControllerStyle(true);
        strategy.setControllerMappingHyphenStyle(true);
        strategy.setEntityColumnConstant(true);
        strategy.setInclude(
                ".*"
        );
        strategy.setTablePrefix(pc.getModuleName() + "_");
        mpg.setStrategy(strategy);
        mpg.setTemplateEngine(new FreemarkerTemplateEngine());

        mpg.getGlobalConfig().setFileOverride(true).setSwagger2(true);
        mpg.execute();
    }
}
