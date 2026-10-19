package com.yourcompany.payment.auth.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded platform-thread pool for interaction recording.
 *
 * Not virtual threads: JDK 25 supports them safely, but they are switched off by
 * decision to match the existing production deployment model. If that changes, note that
 * the thread pool is currently acting as an implicit cap on concurrent upstream calls —
 * removing it makes the bulkheads on the LMS and SMS clients the only backpressure the
 * platform has.
 *
 * CallerRunsPolicy on saturation means the request thread performs the insert itself,
 * slowing one request rather than silently discarding an interaction record.
 *
 * MDC does not cross the async boundary, so the correlation id is copied into the task's
 * own context by the decorator below.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("applicationTaskExecutor")
    public AsyncTaskExecutor applicationTaskExecutor(
            @Value("${app.async.core-pool-size:8}") int corePoolSize,
            @Value("${app.async.max-pool-size:32}") int maxPoolSize,
            @Value("${app.async.queue-capacity:500}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("auth-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);

        executor.setTaskDecorator(runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        });

        executor.initialize();
        return executor;
    }
}
