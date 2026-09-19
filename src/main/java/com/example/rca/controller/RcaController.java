package com.example.rca.controller;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 本地开发用的极简入口。等比赛模板到手，把 AguiController 换回来即可，
 * agent / toolkit / skills 这几层不用动。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RcaController {

    private final HarnessAgent agent;

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    /** 直接分析一个案例，返回完整报告文本。本地验证最常用的就是这个。 */
    @PostMapping("/analyze/{caseId}")
    public Mono<String> analyze(@PathVariable String caseId) {
        String prompt = "请分析故障案例 " + caseId + "，按 AGENTS.md 规定的流程走完，最后调用 write_report 落盘。";
        return run(prompt, "analyze-" + caseId);
    }

    @PostMapping("/chat")
    public Mono<String> chat(@RequestBody ChatRequest request) {
        String sid = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();
        return run(request.getMessage(), sid);
    }

    /** 想看 agent 中间在调哪些工具时用这个。 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request) {
        String sid = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();
        return agent.streamEvents(new UserMessage(request.getMessage()), ctx(sid))
                .map(this::describe)
                .filter(s -> !s.isEmpty())
                .map(s -> ServerSentEvent.<String>builder().data(s).build());
    }

    private Mono<String> run(String prompt, String sessionId) {
        return agent.streamEvents(new UserMessage(prompt), ctx(sessionId))
                .filter(e -> e instanceof TextBlockDeltaEvent)
                .map(e -> {
                    String d = ((TextBlockDeltaEvent) e).getDelta();
                    return d != null ? d : "";
                })
                .reduce("", String::concat)
                .doOnError(err -> log.error("agent 执行失败", err));
    }

    private RuntimeContext ctx(String sessionId) {
        return RuntimeContext.builder().sessionId(sessionId).userId("local").build();
    }

    private String describe(AgentEvent event) {
        if (event instanceof ToolCallStartEvent t) {
            return "[TOOL] " + t.getToolCallName();
        }
        if (event instanceof TextBlockDeltaEvent d) {
            return d.getDelta() == null ? "" : d.getDelta();
        }
        return "";
    }

    @Data
    public static class ChatRequest {
        private String sessionId;
        private String message;
    }
}
