package com.jcaa.usersmanagement.infrastructure.config;

import java.util.concurrent.RejectedExecutionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Slf4j
@EnableAsync
@Configuration(proxyBeanMethods = false)
public class AsyncNotificationConfig {

  private static final int CORE_POOL_SIZE = 2;
  private static final int MAX_POOL_SIZE = 4;
  private static final int QUEUE_CAPACITY = 100;
  private static final int SHUTDOWN_WAIT_SECONDS = 30;

  @Bean(name = "notificationExecutor")
  public ThreadPoolTaskExecutor notificationExecutor() {
    final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(CORE_POOL_SIZE);
    executor.setMaxPoolSize(MAX_POOL_SIZE);
    executor.setQueueCapacity(QUEUE_CAPACITY);
    executor.setThreadNamePrefix("email-notification-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
    executor.setRejectedExecutionHandler(rejectionHandler());
    executor.initialize();
    return executor;
  }

  @Bean
  public AsyncUncaughtExceptionHandler notificationExceptionHandler() {
    return (exception, method, parameters) ->
        log.error(
            "[EmailNotificationService] fallo no controlado en {}. Causa: {}",
            method.getName(),
            exception.getMessage(),
            exception);
  }

  static RejectedExecutionHandler rejectionHandler() {
    return (task, executor) ->
        log.error(
            "[EmailNotificationService] cola llena; notificacion descartada. "
                + "activeTasks={} queueSize={}",
            executor.getActiveCount(),
            executor.getQueue().size());
  }
}
