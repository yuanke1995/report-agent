package com.wisesoft.agent.util;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SSE 事件下发（协议与 dtbd-ai-service 保持一致，事件类型按报表场景扩充）
 * <p>
 * 事件类型：
 * <ul>
 *   <li>{@code stage}   —— 阶段文案，前端显示"正在理解问题…"这类提示</li>
 *   <li>{@code step}    —— Agent 执行步骤（结构化），前端画执行轨迹进度条。
 *                          报表场景这个尤其重要：用户需要看见"选了哪些表、生成了什么 SQL、
 *                          校验有没有过"，否则一个数字扔出来没人敢信。</li>
 *   <li>{@code token}   —— 文本增量</li>
 *   <li>{@code sql}     —— 最终执行的 SQL，前端折叠面板展示，可编辑重跑</li>
 *   <li>{@code data}    —— 查询结果集</li>
 *   <li>{@code chart}   —— 图表配置</li>
 *   <li>{@code clarify} —— 反问澄清，前端渲染成选项按钮</li>
 *   <li>{@code warn}    —— 降级提示（fail-loud，不静默）</li>
 *   <li>{@code error}   —— 错误</li>
 *   <li>{@code done}    —— 结束，附带本轮元信息</li>
 * </ul>
 * 客户端断开时 send 抛 IOException，这里吞掉——调用方通过 Disposable 停止上游生成，
 * 不需要每个发送点都处理断连。
 *
 * @author yuanke
 */
@Slf4j
public final class SseSender {

    private SseSender() {
    }

    /** 发送纯文本内容的事件 */
    public static void send(SseEmitter emitter, String type, String content, String sessionId) {
        emit(emitter, type, JSON.toJSONString(content), sessionId);
    }

    /** 发送结构化对象的事件（对象会被序列化成 JSON 放进 content） */
    public static void sendJson(SseEmitter emitter, String type, Object payload, String sessionId) {
        emit(emitter, type, JSON.toJSONString(JSON.toJSONString(payload)), sessionId);
    }

    private static void emit(SseEmitter emitter, String type, String jsonQuotedContent, String sessionId) {
        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data("{\"type\":\"" + type + "\",\"content\":" + jsonQuotedContent
                            + ",\"sessionId\":\"" + sessionId + "\"}"));
        } catch (IOException e) {
            // 客户端断开，忽略；上游由调用方 dispose
        } catch (IllegalStateException e) {
            // emitter 已 complete，忽略
        }
    }

    public static void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已完成或已断开
        }
    }
}
