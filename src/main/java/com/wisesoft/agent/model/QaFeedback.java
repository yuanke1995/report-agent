package com.wisesoft.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 回答反馈。差评样本是评估集的活水来源——
 * 用户说"这个数字不对"，比任何 benchmark 都更接近真实使用场景。
 *
 * @author yuanke
 */
@Data
@TableName("r_feedback")
public class QaFeedback {

    @TableId(value = "feedback_id", type = IdType.AUTO)
    private Long feedbackId;

    @TableField("message_id")
    private String messageId;

    @TableField("user_id")
    private String userId;

    /** 1=有用 -1=没用 */
    private Integer rating;

    /** 差评原因分类：数字不对/口径不对/答非所问/查询失败 */
    private String reason;

    private String comment;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
