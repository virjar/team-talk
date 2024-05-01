-keep @proguard.annotation.Keep class * {*;}

-keep class com.virjar.tk.server.mapper.** {*;}
-keep class com.virjar.tk.server.entity.** {*;}
-keep @com.virjar.tk.server.service.base.metric.mql.func.MQLFunction$MQL_FUNC class * {*;}
# mbp
-keep class com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean {*;}

# spring相关
-keep @org.springframework.web.bind.annotation.RestController class * {*;}
-keep @org.springframework.context.annotation.Configuration class * {*;}
-keep @org.springframework.stereotype.Component class * {*;}
-keep @org.springframework.stereotype.Service class * {*;}


# 也是 spring 依赖,  调试时可以加上： SourceFile,LineNumberTable
-keepattributes Signature,*Annotation*

-dontwarn
-dontnote
# 有很多compileOnly级别的依赖，忽略他避免混淆中断
-ignorewarnings

-flattenpackagehierarchy com.virjar.tk.server.0O