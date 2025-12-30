package com.github.bank_transfer.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * AOP aspect for centralized logging of controller and service methods.
 *
 * Uses only @Around advice to avoid duplicate logging that occurs when
 * combining @Before/@After/@AfterThrowing with @Around on the same pointcut.
 */
@Slf4j
@Aspect
@Component
public class LoggingAspect {

    @Pointcut("execution(* com.github.bank_transfer.controller..*(..))")
    public void controllerMethods() {}

    @Pointcut("execution(* com.github.bank_transfer.service..*(..))")
    public void serviceMethods() {}

    /**
     * Logs method entry, exit, execution time, and exceptions.
     *
     */
    @Around("controllerMethods() || serviceMethods()")
    public Object logMethodExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getSignature().getDeclaringTypeName();
        String shortClassName = className.substring(className.lastIndexOf('.') + 1);
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        log.info("{} :: {} :: Entry :: args={}", shortClassName, methodName, Arrays.toString(args));

        long startTime = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long executionTime = System.currentTimeMillis() - startTime;

            log.info("{} :: {} :: Exit :: executionTime={}ms", shortClassName, methodName, executionTime);

            return result;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;

            log.error("{} :: {} :: Exception :: executionTime={}ms :: error={}",
                    shortClassName, methodName, executionTime, e.getMessage());

            throw e;
        }
    }
}
