package com.example.rca.config;

import com.example.rca.tools.AnalysisTools;
import com.example.rca.tools.DocumentTools;
import com.example.rca.tools.ReportTools;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class AgentConfig {

    @Value("${agentscope.dashscope.api-key}")
    private String apiKey;

    @Value("${agentscope.dashscope.model-name:qwen3.6-27b}")
    private String modelName;

    @Value("${agentscope.agent.name:RcaAgent}")
    private String agentName;

    @Value("${agentscope.workspace:./workspace}")
    private String workspacePath;

    @Value("${rca.case-root:./cases}")
    private String caseRoot;

    /**
     * qwen3.6-27b。temperature 压到 0.2：故障定位要的是稳定复现，不是发散。
     */
    @Bean
    public DashScopeChatModel chatModel() {
        GenerateOptions options = GenerateOptions.builder()
                .temperature(0.2)
                .topP(0.8)
                .maxTokens(8192)
                .build();

        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .enableThinking(false)
                .defaultOptions(options)
                .build();
    }

    @Bean
    public Toolkit rcaToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DocumentTools(caseRoot));
        toolkit.registerTool(new AnalysisTools(caseRoot));
        toolkit.registerTool(new ReportTools(workspacePath));
        return toolkit;
    }

    /**
     * 关键：一定要 .workspace(...)。
     * workspace 下的 AGENTS.md 会被自动注入系统提示，skills/ 下的 skill 无需注册即生效，
     * 模型先只看到各 skill 的 description，需要时自己调 load_skill_through_path 拉全文。
     */
    @Bean
    public HarnessAgent agent(DashScopeChatModel chatModel, Toolkit rcaToolkit) {
        Path workspace = Paths.get(workspacePath).toAbsolutePath().normalize();

        return HarnessAgent.builder()
                .name(agentName)
                .description("运维故障根因分析 agent")
                .model(chatModel)
                .toolkit(rcaToolkit)
                .workspace(workspace)
                .maxIters(40)
                .build();
    }
}
