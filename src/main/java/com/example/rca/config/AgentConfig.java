package com.example.rca.config;

import com.example.rca.tools.AnalysisTools;
import com.example.rca.tools.DocumentTools;
import com.example.rca.tools.ReportTools;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Configuration
public class AgentConfig {

    @Value("${agentscope.model.api-key}")
    private String apiKey;

    @Value("${agentscope.model.name}")
    private String modelName;

    @Value("${agentscope.model.base-url:}")
    private String baseUrl;

    @Value("${agentscope.model.temperature:0.2}")
    private Double temperature;

    @Value("${agentscope.model.top-p:0.8}")
    private Double topP;

    @Value("${agentscope.model.max-tokens:16384}")
    private Integer maxTokens;

    @Value("${agentscope.model.enable-thinking:false}")
    private boolean enableThinking;

    @Value("${agentscope.agent.name:RcaAgent}")
    private String agentName;

    @Value("${agentscope.agent.max-iters:40}")
    private int maxIters;

    @Value("${agentscope.workspace:./workspace}")
    private String workspacePath;

    @Value("${rca.case-root:./cases}")
    private String caseRoot;

    // ==================== 模型 ====================

    /** 直连 DashScope。本地开发用这个，只要一个 API key。 */
    @Bean
    @ConditionalOnProperty(name = "agentscope.model.provider", havingValue = "dashscope")
    public ChatModelBase dashscopeModel() {
        log.info("【模型】DashScope 直连, model={}", modelName);
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .enableThinking(enableThinking)
                .defaultOptions(dashscopeOptions())
                .build();
    }

    /** OpenAI 兼容端点。部署到服务器走这个，base-url 指向内网地址。 */
    @Bean
    @ConditionalOnProperty(
            name = "agentscope.model.provider",
            havingValue = "openai",
            matchIfMissing = true)
    public ChatModelBase openAiCompatibleModel() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("provider=openai 时必须配置 agentscope.model.base-url");
        }
        log.info("【模型】OpenAI 兼容端点 {}, model={}", baseUrl, modelName);
        return OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .generateOptions(openAiOptions())
                .build();
    }

    private GenerateOptions.Builder baseOptions() {
        return GenerateOptions.builder()
                .temperature(temperature)
                .topP(topP)
                .maxTokens(maxTokens);
    }

    /**
     * DashScope 的思考开关在 model builder 上（enableThinking），不能在 options 里设 thinkingBudget。
     * 设了但没开 enableThinking，DashScope 会直接拒绝请求。
     */
    private GenerateOptions dashscopeOptions() {
        return baseOptions().build();
    }

    /** OpenAI 兼容通道没有 enableThinking 开关，靠 thinkingBudget=0 关闭思考。 */
    private GenerateOptions openAiOptions() {
        GenerateOptions.Builder b = baseOptions();
        if (!enableThinking) {
            b.thinkingBudget(0);
        }
        return b.build();
    }

    // ==================== 工作区 ====================

    /**
     * 工作区解析成绝对路径，并在启动时校验关键内容。
     *
     * <p>AGENTS.md 或 skills/ 缺失时 agent 照样能起来，但流程约束和判读手册全没了，
     * 报告会安静退化成泛泛而谈，事后极难排查。所以这里直接启动失败。
     */
    @Bean
    public Path workspace() {
        Path ws = Paths.get(workspacePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(ws.resolve("reports"));
            Files.createDirectories(ws.resolve("state"));
        } catch (Exception e) {
            throw new IllegalStateException("工作区目录创建失败: " + ws, e);
        }

        Path agents = ws.resolve("AGENTS.md");
        Path skills = ws.resolve("skills");
        if (!Files.isRegularFile(agents)) {
            throw new IllegalStateException(
                    "缺少 " + agents + "，agent 会失去全部流程约束。确认工作区目录已随程序一起部署。");
        }
        if (!Files.isDirectory(skills)) {
            throw new IllegalStateException("缺少 " + skills + "，agent 会失去全部判读手册。");
        }

        long count = -1;
        try (var s = Files.list(skills)) {
            count = s.filter(Files::isDirectory).count();
        } catch (Exception ignored) {
        }
        log.info("【工作区】{} (skills: {} 个)", ws, count);
        return ws;
    }

    // ==================== 工具与 Agent ====================

    @Bean
    public Toolkit rcaToolkit() {
        Path cases = Paths.get(caseRoot).toAbsolutePath().normalize();
        log.info("【案例目录】{}", cases);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DocumentTools(cases.toString()));
        toolkit.registerTool(new AnalysisTools(cases.toString()));
        toolkit.registerTool(new ReportTools(workspacePath));
        return toolkit;
    }

    /**
     * 用 ObjectProvider 取 Studio 钩子：StudioConfig 在未启用时返回 null，
     * 直接按类型注入会失败。同时这个参数保证了 studioMessageHook 一定在 agent 之前创建，
     * 否则钩子挂不上去，而且日志看起来一切正常，非常难排查。
     */
    @Bean
    public HarnessAgent agent(
            ChatModelBase chatModel,
            Toolkit rcaToolkit,
            Path workspace,
            ObjectProvider<StudioMessageHook> studioHookProvider) {

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentName)
                .description("运维故障根因分析 agent")
                .model(chatModel)
                .toolkit(rcaToolkit)
                // workspace 下的 AGENTS.md 自动注入系统提示，skills/ 放好即生效
                .workspace(workspace)
                .maxIters(maxIters)
                // 同 sessionId 跨进程恢复，重跑同一个案例不用从头来
                .stateStore(new JsonFileAgentStateStore(workspace.resolve("state")))
                // 一次分析几十轮工具调用，不压缩会撑爆上下文
                .compaction(
                        CompactionConfig.builder()
                                .triggerMessages(40)
                                .keepMessages(15)
                                .flushBeforeCompact(true)
                                .build());

        StudioMessageHook hook = studioHookProvider.getIfAvailable();
        if (hook != null) {
            builder.hook(hook);
            log.info("【Agent】已挂载 Studio 上报钩子");
        }

        return builder.build();
    }
}