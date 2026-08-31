package com.wisesoft.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @author yuanke
 */
@Data
@Schema(description = "问答请求")
public class ChatRequest {

    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题过长（最多 500 字）")
    @Schema(description = "自然语言问题", example = "最近半年每个月的销售额是多少")
    private String question;

    @Schema(description = "会话 ID，留空则新建会话")
    private String sessionId;
}
