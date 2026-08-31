package com.wisesoft.agent.service;

import com.wisesoft.agent.agent.AgentRunContext;
import com.wisesoft.agent.agent.AgentStep;
import com.wisesoft.agent.mapper.QueryAuditMapper;
import com.wisesoft.agent.model.QueryAudit;
import com.wisesoft.agent.sql.QueryResult;
import com.wisesoft.agent.thread.ThreadPoolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 查询审计落库。
 * <p>
 * 走共享后台线程池异步写：审计不能拖慢问答响应，但也不能丢——
 * 提交失败时打 error 日志（fail-loud），不静默。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int SQL_MAX = 60000;
    private static final int ERR_MAX = 1000;

    private final QueryAuditMapper auditMapper;

    public void record(AgentRunContext ctx, String messageId, String error, long totalMs) {
        QueryAudit a = new QueryAudit();
        a.setSessionId(ctx.getSessionId());
        a.setMessageId(messageId);
        a.setUserId(ctx.getUserId());
        a.setQuestion(ctx.getQuestion());
        a.setRoute(ctx.getRoute() == null ? "none" : ctx.getRoute());
        a.setTemplateId(ctx.getTemplateId());
        a.setRepairCount(ctx.getRepairCount());
        a.setStepCount(ctx.getSteps().size());
        a.setTotalMs(totalMs);
        a.setSuccess(error == null && ctx.hasResult());
        a.setErrorMsg(truncate(error, ERR_MAX));
        a.setCreatedAt(LocalDateTime.now());

        QueryResult last = ctx.lastResult();
        if (last != null) {
            a.setFinalSql(truncate(last.getSql(), SQL_MAX));
            a.setRowCount(last.rowCount());
            a.setSqlMs(ctx.getResults().stream().mapToLong(QueryResult::getElapsedMs).sum());
        }
        // 有失败步骤说明中途被拒绝过，记下第一条拒绝原因供归因
        a.setGuardPassed(ctx.getSteps().stream().noneMatch(s -> s.getStatus() == AgentStep.Status.FAILED));
        ctx.getSteps().stream()
                .filter(s -> s.getStatus() == AgentStep.Status.FAILED)
                .findFirst()
                .ifPresent(s -> a.setGuardReject(truncate(s.getError(), 500)));

        boolean submitted = ThreadPoolManager.execute(() -> {
            try {
                auditMapper.insert(a);
            } catch (Exception e) {
                log.error("[FAIL-LOUD] 审计落库失败 sessionId={}: {}", ctx.getSessionId(), e.getMessage());
            }
        });
        if (!submitted) {
            log.error("[FAIL-LOUD] 审计任务提交失败（队列满或线程池已关闭）sessionId={}", ctx.getSessionId());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
