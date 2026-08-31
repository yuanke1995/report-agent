package com.wisesoft.agent.controller;

import com.wisesoft.agent.common.BizException;
import com.wisesoft.agent.dto.ResultJson;
import com.wisesoft.agent.mapper.AgentMessageMapper;
import com.wisesoft.agent.mapper.QaFeedbackMapper;
import com.wisesoft.agent.model.AgentMessage;
import com.wisesoft.agent.model.QaFeedback;
import com.wisesoft.agent.service.SessionService;
import com.wisesoft.agent.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 反馈闭环：用户对某轮回答的 👍/👎。
 * <p>
 * 差评样本会回流成评估集（阶段 4 的评测器），这是 AI 系统
 * 靠数据迭代而非拍脑袋改 prompt 的前提。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Tag(name = "反馈", description = "回答评价与差评回流")
public class FeedbackController {

    private static final List<String> REASONS = List.of("数字不对", "口径不对", "答非所问", "查询失败");

    private final QaFeedbackMapper feedbackMapper;
    private final AgentMessageMapper messageMapper;
    private final SessionService sessionService;

    @Operation(summary = "提交回答反馈",
            description = "rating=1 有用 / -1 没用；差评需带 reason（数字不对/口径不对/答非所问/查询失败）")
    @PostMapping("/feedback")
    public ResultJson<Map<String, Object>> feedback(@RequestBody Map<String, Object> body,
                                                    HttpServletRequest httpRequest) {
        String messageId = String.valueOf(body.getOrDefault("messageId", ""));
        if (messageId.isBlank()) {
            throw new BizException("缺少 messageId");
        }
        int rating;
        try {
            rating = Integer.parseInt(String.valueOf(body.getOrDefault("rating", 0)));
        } catch (NumberFormatException e) {
            throw new BizException("rating 必须是 1 或 -1");
        }
        if (rating != 1 && rating != -1) {
            throw new BizException("rating 必须是 1 或 -1");
        }

        // 归属校验：只能评价自己会话里的回答
        AgentMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new BizException(404, "消息不存在");
        }
        sessionService.assertOwned(message.getSessionId(), UserContext.resolve(httpRequest));

        String reason = String.valueOf(body.getOrDefault("reason", "")).trim();
        if (rating == -1 && !REASONS.contains(reason)) {
            throw new BizException("差评必须选择原因：" + REASONS);
        }
        String comment = String.valueOf(body.getOrDefault("comment", "")).trim();
        if (comment.length() > 500) {
            throw new BizException("补充说明过长（最多 500 字）");
        }

        QaFeedback fb = new QaFeedback();
        fb.setMessageId(messageId);
        fb.setUserId(UserContext.resolve(httpRequest));
        fb.setRating(rating);
        fb.setReason(reason.isEmpty() ? null : reason);
        fb.setComment(comment.isEmpty() ? null : comment);
        fb.setCreatedAt(LocalDateTime.now());
        feedbackMapper.insert(fb);

        return ResultJson.ok(Map.of("feedbackId", fb.getFeedbackId()), rating == 1 ? "感谢反馈" : "已记录，我们会持续改进");
    }
}
