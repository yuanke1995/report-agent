package com.wisesoft.agent.controller;

import com.wisesoft.agent.common.BizException;
import com.wisesoft.agent.config.AgentProperties;
import com.wisesoft.agent.dto.ChatRequest;
import com.wisesoft.agent.dto.ResultJson;
import com.wisesoft.agent.service.AgentService;
import com.wisesoft.agent.service.ConfigService;
import com.wisesoft.agent.service.RateLimitService;
import com.wisesoft.agent.service.SessionService;
import com.wisesoft.agent.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 问答入口（SSE 流式）
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Tag(name = "报表问答", description = "自然语言问数，SSE 流式返回")
public class ChatController {

    private final AgentService agentService;
    private final SessionService sessionService;
    private final RateLimitService rateLimitService;
    private final ConfigService configService;
    private final AgentProperties properties;

    @Operation(summary = "流式问答",
            description = "事件类型：stage(阶段) / step(执行步骤) / token(文本增量) / sql(生成的SQL) / "
                    + "data(结果集) / chart(图表配置) / clarify(反问) / warn / error / done")
    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody @Valid ChatRequest request, HttpServletRequest httpRequest) {
        String userId = UserContext.resolve(httpRequest);
        rateLimitService.checkRateLimit("chat", UserContext.ANONYMOUS.equals(userId)
                ? "ip:" + clientIp(httpRequest) : "user:" + userId);

        String sessionId = resolveSession(request.getSessionId(), userId);

        long timeout = configService.getLong("chat.sseTimeoutMs", properties.getAgent().getSseTimeoutMs());
        SseEmitter emitter = new SseEmitter(timeout);
        agentService.chat(sessionId, userId, request.getQuestion().trim(), emitter);
        return emitter;
    }

    @Operation(summary = "新建会话")
    @PostMapping("/session/new")
    public ResultJson<Map<String, String>> newSession(HttpServletRequest httpRequest) {
        return ResultJson.ok(Map.of("sessionId",
                sessionService.createSession(UserContext.resolve(httpRequest))));
    }

    @Operation(summary = "会话列表")
    @GetMapping("/sessions")
    public ResultJson<?> listSessions(HttpServletRequest httpRequest) {
        return ResultJson.ok(sessionService.listSessions(UserContext.resolve(httpRequest)));
    }

    @Operation(summary = "会话历史")
    @GetMapping("/session/{sessionId}")
    public ResultJson<?> history(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.assertOwned(sessionId, UserContext.resolve(httpRequest));
        return ResultJson.ok(sessionService.history(sessionId));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/session/{sessionId}")
    public ResultJson<String> delete(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.deleteSession(UserContext.resolve(httpRequest), sessionId);
        return ResultJson.ok("会话已删除");
    }

    /** 传了 sessionId 就校验归属；不存在则按当前用户补建（兼容旧客户端） */
    private String resolveSession(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return sessionService.createSession(userId);
        }
        try {
            sessionService.assertOwned(sessionId, userId);
            return sessionId;
        } catch (BizException e) {
            if (e.getCode() == 404) {
                return sessionService.ensureSession(sessionId, userId);
            }
            throw e;
        }
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
