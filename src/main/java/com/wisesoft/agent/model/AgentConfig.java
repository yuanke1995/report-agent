package com.wisesoft.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运行期可调配置项
 *
 * @author yuanke
 */
@Data
@TableName("r_agent_config")
public class AgentConfig {

    @TableId(value = "cfg_key", type = IdType.INPUT)
    private String cfgKey;

    @TableField("cfg_value")
    private String cfgValue;

    @TableField("cfg_desc")
    private String cfgDesc;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
