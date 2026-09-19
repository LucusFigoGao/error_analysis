package com.example.rca.config;

import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope Studio 上报配置。比赛要求 Java 高代码智能体必须引 studio 扩展依赖，
 * 并在 StudioManager 初始化时配置 Project 名称和监控地址。
 *
 * <p>project-name 必须与平台「高代码智能体中心」登记的智能体名称完全一致，否则上报的数据对不上。
 *
 * <p>初始化失败时返回 null 而不抛异常：Studio 只是监控通道，它挂了不应该让整个 agent 起不来。
 * 但日志会打 error，部署后记得确认这行没出现。
 */
@Slf4j
@Configuration
public class StudioConfig {

    @Value("${agentscope.studio.enabled:false}")
    private Boolean enabled;

    /** 上报的 AgentScope Studio 监控地址 */
    @Value("${agentscope.studio.endpoint:}")
    private String endpoint;

    /** 智能体名称，需与平台登记的一致 */
    @Value("${agentscope.studio.project-name:}")
    private String projectName;

    @Bean
    public StudioMessageHook studioMessageHook() {
        if (!Boolean.TRUE.equals(enabled)) {
            log.info("【Studio Tracing】未启用");
            return null;
        }
        if (endpoint == null || endpoint.isBlank() || projectName == null || projectName.isBlank()) {
            log.error("【Studio Tracing】启用了但 endpoint 或 project-name 为空，跳过初始化");
            return null;
        }

        try {
            StudioManager.init()
                    .studioUrl(endpoint)
                    .project(projectName)
                    .runName("run_" + System.currentTimeMillis())
                    .initialize()
                    .block();

            StudioMessageHook hook = new StudioMessageHook(StudioManager.getClient());
            log.info("【Studio Tracing】已启用, endpoint={}, project={}", endpoint, projectName);
            return hook;
        } catch (Exception e) {
            log.error("【Studio Tracing】初始化失败, endpoint={}", endpoint, e);
            return null;
        }
    }
}
