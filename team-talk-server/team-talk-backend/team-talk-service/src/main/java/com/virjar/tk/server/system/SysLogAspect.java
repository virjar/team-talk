package com.virjar.tk.server.system;


import com.virjar.tk.server.controller.UserInfoController;
import com.virjar.tk.server.entity.SysLog;
import com.virjar.tk.server.entity.UserInfo;
import com.virjar.tk.server.mapper.SysLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;


@Slf4j
@Aspect
@Component
public class SysLogAspect {

    @Resource
    private SysLogMapper sysLogMapper;

//    @Resource
//    private AlertService alertService;

    @Pointcut("@annotation(com.virjar.tk.server.system.LoginRequired)")
    public void pointcut() {
    }

    @Around("pointcut()")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        Object result = point.proceed();
        // 保存日志
        saveLog(point);
        return result;
    }

    private static final Set<String> blackList = new HashSet<String>() {
        {
            add(UserInfoController.class.getName() + "#userInfo");
        }
    };

    private void saveLog(ProceedingJoinPoint point) {
        UserInfo user = AppContext.getUser();
        if (user == null) {
            return;
        }
        if (AppContext.isApiUser()) {
            return;
        }

        MethodSignature signature = (MethodSignature) point.getSignature();
        String className = point.getTarget().getClass().getName();
        String methodName = signature.getName();
        String target = className + "#" + methodName;
        if (blackList.contains(target)) {
            return;
        }


        Method method = signature.getMethod();
        SysLog sysLog = new SysLog();
        sysLog.setUsername(AppContext.getUser().getUserName());
        sysLog.setOperation(method.getName());
        String params = Arrays.toString(point.getArgs());
        sysLog.setParams(StringUtils.substring(params, 0, 50));

        sysLog.setMethodName(target);
        log.info("record sys log:" + sysLog);
        sysLogMapper.insert(sysLog);

        // alert
        LoginRequired loginAnnotation = AppContext.getLoginAnnotation();
        if (loginAnnotation.alert()) {
            String message = "系统敏感操作:\n" +
                    "操作人:" + AppContext.getUser().getUserName() + "\n" +
                    "操作接口: " + target + "\n" +
                    "操作参数：" + sysLog.getParams();
            // alertService.sendMessage(message);
        }
    }


}
