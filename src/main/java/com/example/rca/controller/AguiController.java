package com.example.rca.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AG-UI 协议入口，交付给平台用。
 *
 * <p>路径前缀由 application.yml 的 agui.path-prefix 决定，务必换成比赛方分配给你的那个
 */
@Slf4j
@RestController
@RequestMapping("${agui.path-prefix}")
@RequiredArgsConstructor
public class AguiController {

    private final HarnessAgent agent;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agui.default-user-id:local}")
    private String defaultUserId;

    @PostMapping(value = "/ag-ui", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> runAgent(@RequestBody AguiRequest request) {
        String threadId =
                request.getThreadId() != null ? request.getThreadId() : UUID.randomUUID().toString();
        String runId = request.getRunId() != null ? request.getRunId() : UUID.randomUUID().toString();

        // userId 必须是稳定的用户标识：Harness 的用户级 skill 隔离和记忆持久化都按它寻址。
        // 不能用 runId（每次执行都变）或 threadId（同一用户多会话会被当成多个人）。
        String userId = request.getUserId() != null ? request.getUserId() : defaultUserId;

        log.info(
                "【AG-UI 请求】threadId={}, runId={}, userId={}, messages={}",
                threadId,
                runId,
                userId,
                request.getMessages() != null ? request.getMessages().size() : 0);

        RuntimeContext context =
                RuntimeContext.builder().sessionId(threadId).userId(userId).build();

        return agent.streamEvents(buildUserMessage(request), context)
                .map(event -> convertToAguiEvent(event, threadId, runId))
                // 只打日志不够：异常会直接打断流，前端收不到 RUN_ERROR，只会看到流莫名结束。
                .onErrorResume(
                        err -> {
                            log.error("【AG-UI 错误】threadId={}", threadId, err);
                            return Flux.just(errorEvent(err.getMessage(), threadId, runId));
                        })
                .doOnComplete(() -> log.info("【AG-UI 完成】threadId={}, runId={}", threadId, runId));
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    private UserMessage buildUserMessage(AguiRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return new UserMessage("你好");
        }
        Map<String, Object> last = request.getMessages().get(request.getMessages().size() - 1);
        Object content = last.get("content");
        return new UserMessage(content instanceof String s && !s.isBlank() ? s : "你好");
    }

    /** 把 AgentScope 事件转成 AG-UI 协议事件。 */
    private ServerSentEvent<String> convertToAguiEvent(
            AgentEvent event, String threadId, String runId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("threadId", threadId);
            payload.put("runId", runId);
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("eventId", event.getId());

            // ==================== 生命周期 ====================
            if (event instanceof AgentStartEvent) {
                payload.put("type", "RUN_STARTED");

            } else if (event instanceof AgentEndEvent) {
                payload.put("type", "RUN_FINISHED");

            } else if (event instanceof AgentResultEvent resultEvent) {
                payload.put("type", "RUN_FINISHED");
                payload.put(
                        "result",
                        resultEvent.getResult() != null ? resultEvent.getResult().getTextContent() : "");

                // ==================== 模型调用 ====================
            } else if (event instanceof ModelCallStartEvent) {
                payload.put("type", "STEP_STARTED");
                payload.put("stepName", "model_call");

            } else if (event instanceof ModelCallEndEvent) {
                payload.put("type", "STEP_FINISHED");
                payload.put("stepName", "model_call");

                // ==================== 文本消息 ====================
            } else if (event instanceof TextBlockStartEvent) {
                payload.put("type", "TEXT_MESSAGE_START");

            } else if (event instanceof TextBlockDeltaEvent delta) {
                payload.put("type", "TEXT_MESSAGE_CONTENT");
                payload.put("delta", nz(delta.getDelta()));

            } else if (event instanceof TextBlockEndEvent) {
                payload.put("type", "TEXT_MESSAGE_END");

                // ==================== 思考过程 ====================
            } else if (event instanceof ThinkingBlockStartEvent) {
                payload.put("type", "TEXT_THINKING_START");

            } else if (event instanceof ThinkingBlockDeltaEvent delta) {
                payload.put("type", "TEXT_THINKING_CONTENT");
                payload.put("delta", nz(delta.getDelta()));

            } else if (event instanceof ThinkingBlockEndEvent) {
                payload.put("type", "TEXT_THINKING_END");

                // ==================== 工具调用 ====================
            } else if (event instanceof ToolCallStartEvent toolStart) {
                payload.put("type", "TOOL_CALL_START");
                payload.put("toolCallName", nz(toolStart.getToolCallName(), "unknown"));
                payload.put("toolCallId", nz(toolStart.getToolCallId(), UUID.randomUUID().toString()));

            } else if (event instanceof ToolCallDeltaEvent toolDelta) {
                payload.put("type", "TOOL_CALL_ARGS");
                payload.put("toolCallName", nz(toolDelta.getToolCallName(), "unknown"));
                payload.put("toolCallId", nz(toolDelta.getToolCallId(), UUID.randomUUID().toString()));
                payload.put("delta", nz(toolDelta.getDelta()));

            } else if (event instanceof ToolCallEndEvent toolEnd) {
                payload.put("type", "TOOL_CALL_END");
                payload.put("toolCallName", nz(toolEnd.getToolCallName(), "unknown"));
                payload.put("toolCallId", nz(toolEnd.getToolCallId(), UUID.randomUUID().toString()));

            } else if (event instanceof ToolResultStartEvent resultStart) {
                payload.put("type", "TOOL_CALL_RESULT");
                payload.put("status", "started");
                payload.put("toolCallName", nz(resultStart.getToolCallName(), "unknown"));
                payload.put("toolCallId", nz(resultStart.getToolCallId(), "unknown"));

            } else if (event instanceof ToolResultDataDeltaEvent dataDelta) {
                payload.put("type", "TOOL_CALL_RESULT");
                payload.put("status", "streaming");
                payload.put("dataType", "data");
                payload.put("toolCallId", nz(dataDelta.getToolCallId(), "unknown"));
                payload.put("toolCallName", nz(dataDelta.getToolCallName(), "unknown"));
                payload.put("data", dataDelta.getData() != null ? dataDelta.getData().toString() : "{}");

            } else if (event instanceof ToolResultTextDeltaEvent textDelta) {
                payload.put("type", "TOOL_CALL_RESULT");
                payload.put("status", "streaming");
                payload.put("dataType", "text");
                payload.put("toolCallId", nz(textDelta.getToolCallId(), "unknown"));
                payload.put("toolCallName", nz(textDelta.getToolCallName(), "unknown"));
                // 原模板这里写的是 toString()，输出的是对象地址不是工具文本
                payload.put("text", nz(textDelta.getDelta()));

            } else if (event instanceof ToolResultEndEvent resultEnd) {
                payload.put("type", "TOOL_CALL_RESULT");
                payload.put("status", "completed");
                payload.put("toolCallName", nz(resultEnd.getToolCallName(), "unknown"));
                payload.put("toolCallId", nz(resultEnd.getToolCallId(), "unknown"));
                payload.put("state", resultEnd.getState() != null ? resultEnd.getState().name() : "UNKNOWN");

                // ==================== 多模态数据块 ====================
            } else if (event instanceof DataBlockStartEvent) {
                payload.put("type", "MULTIMODAL_BLOCK_START");

            } else if (event instanceof DataBlockDeltaEvent delta) {
                payload.put("type", "MULTIMODAL_BLOCK_CONTENT");
                payload.put("delta", nz(delta.getDelta()));

            } else if (event instanceof DataBlockEndEvent) {
                payload.put("type", "MULTIMODAL_BLOCK_END");

                // ==================== Hint ====================
            } else if (event instanceof HintBlockEvent hint) {
                payload.put("type", "CUSTOM");
                payload.put("customType", "hint");
                payload.put("content", hint.toString());

                // ==================== 人机交互 ====================
            } else if (event instanceof RequireUserConfirmEvent confirmEvent) {
                payload.put("type", "PROCESS_MONITOR_PAUSE");
                payload.put("action", "require_user_confirm");
                payload.put("message", "需要用户确认");
                payload.put("eventId", confirmEvent.getId());

            } else if (event instanceof UserConfirmResultEvent confirmResult) {
                payload.put("type", "PROCESS_MONITOR_ACTION");
                payload.put("action", "user_confirm_result");
                payload.put(
                        "confirmResults",
                        confirmResult.getConfirmResults() != null
                                ? confirmResult.getConfirmResults().toString()
                                : "[]");

            } else if (event instanceof RequireExternalExecutionEvent execEvent) {
                payload.put("type", "PROCESS_MONITOR_ACTION");
                payload.put("action", "require_external_execution");
                payload.put("eventId", execEvent.getId());

            } else if (event instanceof ExternalExecutionResultEvent execResult) {
                payload.put("type", "PROCESS_MONITOR_ACTION");
                payload.put("action", "external_execution_result");
                payload.put("eventId", execResult.getId());
                payload.put("success", true);

                // ==================== 子 Agent ====================
            } else if (event instanceof SubagentExposedEvent subagent) {
                payload.put("type", "CUSTOM");
                payload.put("customType", "subagent_exposed");
                payload.put("subagentId", nz(subagent.getSubagentId(), "unknown"));
                payload.put("agentId", nz(subagent.getAgentId(), "unknown"));
                payload.put("label", nz(subagent.getLabel()));

                // ==================== 异常终止 ====================
            } else if (event instanceof AllToolsDeniedEvent) {
                payload.put("type", "RUN_ERROR");
                payload.put("message", "所有工具调用被拒绝");
                payload.put("code", "TOOLS_DENIED");

            } else if (event instanceof ExceedMaxItersEvent maxIters) {
                payload.put("type", "RUN_ERROR");
                payload.put("message", "超过最大迭代次数");
                payload.put("code", "MAX_ITERS_EXCEEDED");
                payload.put("maxIters", maxIters.getMaxIters());

            } else if (event instanceof RequestStopEvent stopEvent) {
                payload.put("type", "RUN_FINISHED");
                payload.put("reason", "requested_stop");
                payload.put("eventId", stopEvent.getId());

            } else if (event instanceof CustomEvent custom) {
                payload.put("type", "CUSTOM");
                payload.put("customType", nz(custom.getName(), "unknown"));
                payload.put("value", custom.getValue() != null ? custom.getValue() : new LinkedHashMap<>());

                // ==================== 兜底 ====================
            } else {
                payload.put("type", "RAW");
                payload.put("eventType", event.getType() != null ? event.getType().name() : "UNKNOWN");
                payload.put("eventClass", event.getClass().getSimpleName());
                log.debug("【AG-UI 转换】未映射的事件类型: {}", event.getClass().getSimpleName());
            }

            return ServerSentEvent.<String>builder()
                    .data(objectMapper.writeValueAsString(payload))
                    .build();

        } catch (Exception e) {
            log.error("转换事件失败: event={}", event.getClass().getSimpleName(), e);
            return errorEvent("Internal conversion error: " + e.getMessage(), threadId, runId);
        }
    }

    private ServerSentEvent<String> errorEvent(String message, String threadId, String runId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threadId", threadId);
        payload.put("runId", runId);
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("type", "RUN_ERROR");
        payload.put("message", message != null ? message : "Internal error");
        payload.put("code", "500");
        try {
            return ServerSentEvent.<String>builder()
                    .data(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (Exception ex) {
            return ServerSentEvent.<String>builder()
                    .data("{\"type\":\"RUN_ERROR\",\"message\":\"Internal error\"}")
                    .build();
        }
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static String nz(String s, String dft) {
        return s != null ? s : dft;
    }

    @Data
    public static class AguiRequest {
        private String threadId;
        private String runId;
        private String userId;
        private List<Map<String, Object>> messages;
    }
}