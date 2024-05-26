import com.android.build.gradle.internal.tasks.factory.dependsOn
import org.gradle.api.internal.plugins.DefaultJavaAppStartScriptGenerationDetails

plugins {
    java
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    id("org.springdoc.openapi-gradle-plugin") version "1.8.0"
    application
}
val applicationId: String by rootProject.extra
var versionCode: Int by rootProject.extra
var versionName: String by rootProject.extra
var buildTime: String by rootProject.extra
var buildUser: String by rootProject.extra


group = applicationId
version = versionName

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-rsocket")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.5.0")

    implementation("ch.qos.logback:logback-classic:1.4.12")
    implementation("org.slf4j:jcl-over-slf4j:1.7.30")
    implementation("org.slf4j:log4j-over-slf4j:1.7.30")
    implementation("com.google.guava:guava:31.1-jre")
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("commons-io:commons-io:2.10.0")
    implementation("com.alibaba:fastjson:1.2.79")

    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // todo this will be removed
    developmentOnly("com.baomidou:mybatis-plus-generator:3.5.3.2")
    developmentOnly("org.apache.velocity:velocity-engine-core:2.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}


application {
    mainClass = "com.virjar.tk.server.TeamTalkMain"
    applicationName = "TeamTalk"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=utf-8", "-Duser.timezone=GMT+08", "-XX:-OmitStackTraceInFastThrow"
    )
    applicationDistribution.from("${projectDir}/src/main/resources") {
        include("application.properties")
        into("conf/")
    }
    applicationDistribution.from("${projectDir}/dev") {
        include("ddl.sql")
        into("conf/")
    }

    applicationDistribution.from("${projectDir}/web/frontend/build") {
        into("conf/static/")
    }
    applicationDistribution.from("${projectDir}/web/doc/src/.vuepress/dist") {
        into("conf/static/team-talk-doc")
    }
}

tasks.getByPath("startScripts").doFirst {
    (this as CreateStartScripts).let {
        fun wrapScriptGenerator(delegate: ScriptGenerator): ScriptGenerator {
            return ScriptGenerator { details, destination ->
                // 增加一个conf的目录，作为最终目标的classPath，在最终发布的时候，我们需要植入静态资源
                (details as DefaultJavaAppStartScriptGenerationDetails).classpath
                    .add(0, "conf")
                delegate.generateScript(details, destination)
            }
        }
        unixStartScriptGenerator = wrapScriptGenerator(unixStartScriptGenerator)
        windowsStartScriptGenerator = wrapScriptGenerator(windowsStartScriptGenerator)
    }
}

sourceSets {
    main {
        java {
            srcDir("build/generated/java")
        }
    }
}

tasks.compileJava.dependsOn(tasks.register("generateJavaCode") {
    doLast {
        val generatedDir = file("build/generated/java").resolve(
            applicationId.replace('.', '/')
        )
        generatedDir.mkdirs()
        val className = "BuildInfo"
        val sourceFile = File(generatedDir, "$className.java")


        //public static final String gitId ="${rootProject.property("git.commit.id")}";
        sourceFile.writeText(
            """
            package ${applicationId};

            public class $className {
                    public static final int versionCode = ${versionCode};
                    public static final String versionName = "$versionName";
                    public static final String buildTime ="$buildTime";
                    public static final String buildUser ="$buildUser";
            }
        """.trimIndent()
        )
    }
})

afterEvaluate {
    tasks.startScripts.dependsOn(":server:web:frontend:yarnBuild")
    tasks.startScripts.dependsOn(":server:web:doc:yarnBuild")
}


