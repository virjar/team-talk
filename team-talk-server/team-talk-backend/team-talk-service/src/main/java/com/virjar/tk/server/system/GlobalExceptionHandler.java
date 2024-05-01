package com.virjar.tk.server.system;

import com.virjar.tk.server.entity.CommonRes;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import proguard.annotation.Keep;

/**
 * Date: 2021-06-02
 *
 * @author alienhe
 */
@Slf4j
@RestControllerAdvice
@Keep
public class GlobalExceptionHandler {

    @ExceptionHandler(value = {MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public CommonRes<String> handleMethodArgumentNotValid(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException) {
            if (((MethodArgumentNotValidException) exception).getBindingResult().getFieldError() != null) {
                return CommonRes.failed("参数错误：" + ((MethodArgumentNotValidException) exception).getBindingResult()
                        .getFieldError().getDefaultMessage());
            }
        }

        log.error("GlobalException", exception);
        return CommonRes.failed("参数错误：" + exception.getMessage());
    }

    @ExceptionHandler(value = {Exception.class})
    public CommonRes<String> handleUncaughtException(Exception exception) {
        if (isClientAbortException(exception)) {
            // ClientAbortException不打印异常级别日志
            log.info("client abort", exception);
            return CommonRes.failed("unexpected error:" + exception.getMessage());
        }
        log.error("unexpected exception:", exception);
        return CommonRes.failed("unexpected error:" + exception.getMessage());
    }

    private boolean isClientAbortException(Throwable e) {
        if (e == null) {
            return false;
        }
        if (e instanceof ClientAbortException) {
            return true;
        }
        if (e instanceof HttpMessageNotReadableException) {
            return true;
        }

        return isClientAbortException(e.getCause());
    }
}
