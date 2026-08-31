package com.wisesoft.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 查询审计。
 * <p>
 * 报表智能体访问真实经营数据，"谁在什么时候查了什么"必须可回溯。
 * 这张表同时也是评测集的数据来源和成本分析的依据。
 *
 * @author yuanke
 */
@Data
@TableName("r_query_audit")
public class QueryAudit {

    @TableId(value = "audit_id", type = IdType.AUTO)
    private Long auditId;

    @TableField("session_id")
    private String sessionId;

    @TableField("message_id")
    private String messageId;

    @TableField("user_id")
    private String userId;

    private String question;

    /** template / nl2sql / clarify */
    private String route;

    @TableField("template_id")
    private String templateId;

    @TableField("final_sql")
    private String finalSql;

    @TableField("guard_passed")
    private Boolean guardPassed;

    @TableField("guard_reject")
    private String guardReject;

    @TableField("repair_count")
    private Integer repairCount;

    @TableField("step_count")
    private Integer stepCount;

    @TableField("row_count")
    private Integer rowCount;

    private Boolean success;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("prompt_tokens")
    private Integer promptTokens;

    @TableField("total_ms")
    private Long totalMs;

    @TableField("sql_ms")
    private Long sqlMs;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
