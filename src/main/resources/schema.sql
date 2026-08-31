-- ============================================================
-- 智能体元数据库建表（report_agent）
-- CREATE TABLE IF NOT EXISTS，重复执行安全，随服务启动自动执行
-- ============================================================

-- 运行期可调配置。提示词放这里而不是代码里：SQL 生成的 prompt 需要反复迭代，
-- 每改一次重启服务不现实。
CREATE TABLE IF NOT EXISTS r_agent_config
(
    cfg_key    VARCHAR(64)  NOT NULL COMMENT '配置项 key',
    cfg_value  TEXT COMMENT '配置值',
    cfg_desc   VARCHAR(255) COMMENT '说明（设置页展示）',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (cfg_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='运行期配置';

CREATE TABLE IF NOT EXISTS r_session
(
    session_id VARCHAR(64)  NOT NULL COMMENT '会话ID',
    user_id    VARCHAR(64)  NOT NULL COMMENT '用户标识（网关透传 X-User-Id）',
    title      VARCHAR(255) COMMENT '会话标题（取首个问题）',
    pinned     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否置顶',
    deleted    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id),
    KEY idx_user (user_id, deleted, pinned, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='会话';

CREATE TABLE IF NOT EXISTS r_message
(
    message_id  VARCHAR(64) NOT NULL COMMENT '消息ID',
    session_id  VARCHAR(64) NOT NULL COMMENT '会话ID',
    role        VARCHAR(16) NOT NULL COMMENT 'user / assistant',
    content     MEDIUMTEXT COMMENT '消息正文',
    -- 一轮问答的完整产物：执行轨迹、SQL、结果集、图表配置，
    -- 存 JSON 是为了恢复会话时能原样重现，而不是只剩一段文字。
    payload     MEDIUMTEXT COMMENT '结构化产物 JSON（trace/sql/data/chart）',
    deleted     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id),
    KEY idx_session (session_id, deleted, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息';

-- 审计：每一次落到业务库的查询都要留痕。
-- 报表智能体访问的是真实经营数据，"谁在什么时候查了什么"必须可回溯。
-- 这张表同时也是评测集的数据来源和成本分析的依据。
CREATE TABLE IF NOT EXISTS r_query_audit
(
    audit_id      BIGINT       NOT NULL AUTO_INCREMENT,
    session_id    VARCHAR(64) COMMENT '会话ID',
    message_id    VARCHAR(64) COMMENT '消息ID',
    user_id       VARCHAR(64)  NOT NULL COMMENT '发起用户',
    question      TEXT COMMENT '原始问题',
    route         VARCHAR(16)  NOT NULL COMMENT '路由：template / nl2sql / clarify',
    template_id   VARCHAR(64) COMMENT '命中的模板ID',
    final_sql     TEXT COMMENT '最终执行的 SQL',
    guard_passed  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'SqlGuard 是否通过',
    guard_reject  VARCHAR(500) COMMENT '被拒原因',
    repair_count  INT          NOT NULL DEFAULT 0 COMMENT '自修正次数',
    step_count    INT          NOT NULL DEFAULT 0 COMMENT 'ReAct 轮次',
    row_count     INT COMMENT '返回行数',
    success       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否成功',
    error_msg     VARCHAR(1000) COMMENT '失败原因',
    prompt_tokens INT COMMENT '输入 token（估算）',
    total_ms      BIGINT COMMENT '总耗时(ms)',
    sql_ms        BIGINT COMMENT 'SQL 执行耗时(ms)',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (audit_id),
    KEY idx_session (session_id),
    KEY idx_created (created_at),
    KEY idx_route (route, success)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='查询审计';

-- 反馈闭环：用户对某轮回答的评价。差评样本回流成评测集，
-- 这是 AI 系统靠数据迭代而非拍脑袋改 prompt 的前提。
CREATE TABLE IF NOT EXISTS r_feedback
(
    feedback_id BIGINT      NOT NULL AUTO_INCREMENT,
    message_id  VARCHAR(64) NOT NULL COMMENT '被评价的消息ID',
    user_id     VARCHAR(64) NOT NULL,
    rating      TINYINT     NOT NULL COMMENT '1=有用 -1=没用',
    reason      VARCHAR(64) COMMENT '差评原因分类：数字不对/口径不对/答非所问/查询失败',
    comment     VARCHAR(1000) COMMENT '补充说明',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (feedback_id),
    UNIQUE KEY uk_message_user (message_id, user_id),
    KEY idx_rating (rating, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='回答反馈';
