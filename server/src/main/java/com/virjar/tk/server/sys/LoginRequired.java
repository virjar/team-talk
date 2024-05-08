package com.virjar.tk.server.sys;



import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginRequired {
    boolean forAdmin() default false;

    boolean apiToken() default false;

    boolean alert() default false;
}
