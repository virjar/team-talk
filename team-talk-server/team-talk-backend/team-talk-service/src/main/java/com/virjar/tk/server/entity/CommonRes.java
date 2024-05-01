package com.virjar.tk.server.entity;

import com.virjar.tk.server.utils.CommonUtils;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

@Data
@AllArgsConstructor
public class CommonRes<T> {
    private int status = statusOK;
    private String message;
    private T data;

    public static final int statusOK = 0;
    public static final int statusError = -1;
    public static final int statusBadRequest = -2;
    public static final int statusNeedLogin = -4;
    public static final int statusLoginExpire = -5;
    public static final int statusDeny = -6;

    public static <T> CommonRes<T> success(T t) {
        CommonRes<T> ret = new CommonRes<>();
        ret.status = statusOK;
        ret.message = null;
        ret.data = t;
        return ret;

    }

    public CommonRes() {
    }


    public static <T> CommonRes<T> failed(Throwable throwable) {
        return failed(CommonUtils.throwableToString(throwable));
    }

    public static <T> CommonRes<T> failed(String message) {
        return failed(statusError, message);
    }

    public static <T> CommonRes<T> failed(int status, String message) {
        CommonRes<T> ret = new CommonRes<>();
        ret.status = status;
        ret.message = message;
        return ret;
    }

    public boolean isOk() {
        return status == statusOK;
    }

    public <TN> CommonRes<TN> errorTransfer() {
        return CommonRes.failed(status, message);
    }

    public <TN> CommonRes<TN> transform(Function<T, TN> function) {
        if (isOk()) {
            return CommonRes.success(function.apply(data));
        }
        return errorTransfer();
    }

    public CommonRes<T> accept(Consumer<CommonRes<T>> consumer) {
        consumer.accept(this);
        return this;
    }

    public CommonRes<T> ifOk(Consumer<T> consumer) {
        if (isOk()) {
            consumer.accept(data);
        }
        return this;
    }

    public <NT> CommonRes<NT> callIfOk(Callable<CommonRes<NT>> callable) {
        if (isOk()) {
            try {
                return callable.call();
            } catch (Exception e) {
                return CommonRes.failed(e);
            }
        }
        return errorTransfer();
    }

    public void changeFailed(String msg) {
        this.status = -1;
        this.message = msg;
    }

    public static <T> CommonRes<T> ofPresent(T t) {
        if (t == null) {
            return CommonRes.failed("record not found");
        }
        return CommonRes.success(t);
    }

    public static <T> CommonRes<T> call(Callable<T> callable) {
        try {
            return ofPresent(callable.call());
        } catch (Exception e) {
            return CommonRes.failed(e);
        }
    }
}
