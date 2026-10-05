package com.taskflow.engine.local.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 本地引擎自动配置。
 *
 * <p>{@code @ConditionalOnProperty} 加在「自动配置类」上（而不是某一个 Bean 上），
 * 保证切换引擎时「队列 + 搬运工 + worker + 监控 + 恢复」整体一起开关，避免两个引擎同时处理同一批任务。
 */
@AutoConfiguration
@ComponentScan("com.taskflow.engine.local")
@EnableConfigurationProperties(PipelineProperties.class)
@ConditionalOnProperty(name = "taskflow.engine", havingValue = "local", matchIfMissing = true)
@EnableScheduling   // 引擎内部需要定时任务（队列监控、补偿扫描）；宿主不要重复开启
public class LocalEngineAutoConfiguration {
}
