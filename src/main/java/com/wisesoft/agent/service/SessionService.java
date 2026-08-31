package com.wisesoft.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.agent.common.BizException;
import com.wisesoft.agent.mapper.AgentMessageMapper;
import com.wisesoft.agent.mapper.AgentSessionMapper;
import com.wisesoft.agent.model.AgentMessage;
import com.wisesoft.agent.model.AgentSession;
import com.wisesoft.agent.util.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 会话与消息（搬自 dtbd-ai-service 的模型，按报表场景精简）
 * <p>
 * 归属校验是这里的重点：会话按用户隔离，任何读写都先过 assertOwned。
 * 报表智能体查的是真实经营数据，跨用户读到别人的历史会话等同于数据泄露。
 * anonymous 保留为无网关本地调试的共享池。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final int TITLE_MAX = 60;

    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;

    public String createSession(String userId) {
        AgentSession s = new AgentSession();
        s.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        s.setUserId(userId);
        s.setPinned(false);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(s);
        return s.getSessionId();
    }

    /** 客户端传了未知 sessionId 时按当前用户补建，兼容旧客户端 */
    public String ensureSession(String sessionId, String userId) {
        AgentSession s = new AgentSession();
        s.setSessionId(sessionId);
        s.setUserId(userId);
        s.setPinned(false);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(s);
        return sessionId;
    }

    /**
     * 归属校验。anonymous 名下的会话对所有用户可见（无网关的本地调试池），
     * 其余一律要求 userId 完全匹配。
     */
    public void assertOwned(String sessionId, String userId) {
        AgentSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new BizException(404, "会话不存在");
        }
        if (UserContext.ANONYMOUS.equals(s.getUserId())) {
            return;
        }
        if (!s.getUserId().equals(userId)) {
            throw new BizException(403, "无权访问该会话");
        }
    }

    public List<AgentSession> listSessions(String userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<AgentSession>()
                .in(AgentSession::getUserId, List.of(userId, UserContext.ANONYMOUS))
                .orderByDesc(AgentSession::getPinned)
                .orderByDesc(AgentSession::getUpdatedAt));
    }

    public List<AgentMessage> history(String sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId)
                .orderByAsc(AgentMessage::getCreatedAt));
    }

    public String appendMessage(String sessionId, String role, String content, String payload) {
        AgentMessage m = new AgentMessage();
        m.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setPayload(payload);
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);

        AgentSession s = sessionMapper.selectById(sessionId);
        if (s != null) {
            s.setUpdatedAt(LocalDateTime.now());
            // 首个用户问题作为会话标题
            if ((s.getTitle() == null || s.getTitle().isBlank()) && "user".equals(role) && content != null) {
                s.setTitle(content.length() > TITLE_MAX ? content.substring(0, TITLE_MAX) : content);
            }
            sessionMapper.updateById(s);
        }
        return m.getMessageId();
    }

    public void deleteSession(String userId, String sessionId) {
        assertOwned(sessionId, userId);
        sessionMapper.deleteById(sessionId);
        messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId));
    }
}
