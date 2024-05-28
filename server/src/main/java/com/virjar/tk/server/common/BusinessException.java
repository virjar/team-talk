package com.virjar.tk.server.common;

import lombok.Getter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    private final String msg;

    public BusinessException(String msg) {
        super(msg);
        this.code = -1;
        this.msg = msg;
    }


    public BusinessException(int code, String msg) {
        super(msg);
        this.code = code;
        this.msg = msg;
    }

    public BusinessException(Throwable cause, int code, String msg) {
        super(msg, cause);
        this.code = code;
        this.msg = msg;
    }

    public <T> Mono<T> m() {
        return Mono.error(() -> new BusinessException(code, msg));
    }

    public static <T> Mono<T> errorM(String msg) {
        return Mono.error(new BusinessException(msg));
    }

    public <T> Flux<T> f() {
        return Flux.error(() -> new BusinessException(code, msg));
    }

    public static <T> Flux<T> errorF(String msg) {
        return Flux.error(new BusinessException(msg));
    }


    public interface USER {
        BusinessException REGISTER_USER_PASS_EMPTY = new BusinessException("user name or password is empty");
        BusinessException REGISTER_ILLEGAL_USER_NAME = new BusinessException("userName contain illegal character");
        BusinessException REGISTER_DUPLICATE_USER = new BusinessException("this user already exist");
        BusinessException REGISTER_CANNOT_REGISTER = new BusinessException("当前系统不允许注册新用户，详情请联系管理员");

        BusinessException USER_NOT_EXIST = new BusinessException("user not exist");
        BusinessException CAN_NOT_SETUP_ADMIN_FOR_DEMO_SITE = new BusinessException("can not setup admin for demo site");
    }

    public interface SYSTEM {
        BusinessException CANNOT_CHANGE_SETTING_FOR_DEMO_SITE = new BusinessException("can not change setting for demo site");
        BusinessException RECORD_NOT_FOUND = new BusinessException("record not found");
    }

}
