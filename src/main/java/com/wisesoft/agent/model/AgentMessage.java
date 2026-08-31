package com.wisesoft.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条消息。assistant 消息的 payload 存本轮的完整产物
 * （执行轨迹 / SQL / 结果集 / 图表配置），用于恢复会话时原样重现。
 *
 * @author yuanke
 */
@Data
@TableName("r_message")
public class AgentMessage {

    @TableId(value = "message_id", type = IdType.INPUT)
    private String messageId;

    @TableField("session_id")
    private String sessionId;

    private String role;

    private String content;

    private String payload;

    @TableLogic
    private Integer deleted;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
