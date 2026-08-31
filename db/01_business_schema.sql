-- ============================================================
-- 业务库：模拟电商销售数据仓（report_demo）
--
-- 设计意图：这套表刻意保留了真实企业里最常见的「口径歧义源」，
-- 用来验证语义层是否真的在起作用。典型歧义：
--   1. 订单金额有 order_amount(应付) / pay_amount(实付) / refund_amount(退款)
--      —— 问「销售额」到底指哪个？含不含退款？
--   2. order_status 有 6 种状态，GMV 算不算未支付的订单？
--   3. 地区既有大区(region_name)又有省(province)、市(city)
--      —— 问「华东区」和「江苏省」走的是同一列吗？
--   4. 客户等级、渠道都是英文枚举值存储，中文提问无法直接映射
-- 这些都无法从 DDL 推断，必须由 semantic-model 显式声明。
-- ============================================================

CREATE DATABASE IF NOT EXISTS report_demo
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE report_demo;

DROP TABLE IF EXISTS fact_order_item;
DROP TABLE IF EXISTS fact_order;
DROP TABLE IF EXISTS dim_customer;
DROP TABLE IF EXISTS dim_product;
DROP TABLE IF EXISTS dim_region;
DROP TABLE IF EXISTS dim_date;

-- ------------------------------------------------------------
-- 维度表
-- ------------------------------------------------------------

CREATE TABLE dim_date
(
    date_key    DATE        NOT NULL COMMENT '日期',
    year_num    SMALLINT    NOT NULL COMMENT '年',
    quarter_num TINYINT     NOT NULL COMMENT '季度 1-4',
    month_num   TINYINT     NOT NULL COMMENT '月 1-12',
    year_month_str CHAR(7)  NOT NULL COMMENT '年月 yyyy-MM',
    day_of_week TINYINT     NOT NULL COMMENT '星期几 1=周一 7=周日',
    is_weekend  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否周末 1=是',
    PRIMARY KEY (date_key),
    KEY idx_year_month (year_month_str)
) ENGINE = InnoDB COMMENT ='日期维度表';

CREATE TABLE dim_region
(
    region_id   INT         NOT NULL AUTO_INCREMENT COMMENT '地区ID',
    region_name VARCHAR(20) NOT NULL COMMENT '大区：华东/华北/华南/华中/西南/西北/东北',
    province    VARCHAR(30) NOT NULL COMMENT '省份',
    city        VARCHAR(30) NOT NULL COMMENT '城市',
    PRIMARY KEY (region_id),
    KEY idx_region_name (region_name),
    KEY idx_province (province)
) ENGINE = InnoDB COMMENT ='地区维度表（大区-省-市三级同表）';

CREATE TABLE dim_product
(
    product_id   INT            NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    product_code VARCHAR(32)    NOT NULL COMMENT '商品编码',
    product_name VARCHAR(100)   NOT NULL COMMENT '商品名称',
    category_l1  VARCHAR(30)    NOT NULL COMMENT '一级类目',
    category_l2  VARCHAR(30)    NOT NULL COMMENT '二级类目',
    brand        VARCHAR(30)    NOT NULL COMMENT '品牌',
    unit_cost    DECIMAL(12, 2) NOT NULL COMMENT '单位成本（元）',
    list_price   DECIMAL(12, 2) NOT NULL COMMENT '标价（元）',
    status       VARCHAR(16)    NOT NULL DEFAULT 'on_sale' COMMENT '状态：on_sale=在售 off_shelf=下架',
    PRIMARY KEY (product_id),
    UNIQUE KEY uk_product_code (product_code),
    KEY idx_category (category_l1, category_l2)
) ENGINE = InnoDB COMMENT ='商品维度表';

CREATE TABLE dim_customer
(
    customer_id    INT         NOT NULL AUTO_INCREMENT COMMENT '客户ID',
    customer_code  VARCHAR(32) NOT NULL COMMENT '客户编码',
    customer_name  VARCHAR(60) NOT NULL COMMENT '客户名称',
    customer_level VARCHAR(16) NOT NULL COMMENT '等级：normal=普通 silver=银卡 gold=金卡 platinum=铂金',
    register_date  DATE        NOT NULL COMMENT '注册日期',
    region_id      INT         NOT NULL COMMENT '归属地区ID',
    channel        VARCHAR(16) NOT NULL COMMENT '注册渠道：app/web/mini_program/offline',
    PRIMARY KEY (customer_id),
    UNIQUE KEY uk_customer_code (customer_code),
    KEY idx_level (customer_level),
    KEY idx_region (region_id)
) ENGINE = InnoDB COMMENT ='客户维度表';

-- ------------------------------------------------------------
-- 事实表
-- ------------------------------------------------------------

CREATE TABLE fact_order
(
    order_id        BIGINT         NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    order_no        VARCHAR(32)    NOT NULL COMMENT '订单号',
    customer_id     INT            NOT NULL COMMENT '客户ID',
    region_id       INT            NOT NULL COMMENT '收货地区ID',
    order_date      DATE           NOT NULL COMMENT '下单日期',
    order_status    VARCHAR(16)    NOT NULL COMMENT '状态：created=待支付 paid=已支付 shipped=已发货 completed=已完成 refunded=已退款 cancelled=已取消',
    channel         VARCHAR(16)    NOT NULL COMMENT '下单渠道：app/web/mini_program/offline',
    order_amount    DECIMAL(14, 2) NOT NULL COMMENT '订单应付金额（元，未扣优惠前的商品总额）',
    discount_amount DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '优惠金额（元）',
    freight_amount  DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '运费（元）',
    pay_amount      DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '实付金额（元）= 应付 - 优惠 + 运费；未支付订单为 0',
    refund_amount   DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '退款金额（元），仅 refunded 状态非 0',
    item_count      INT            NOT NULL DEFAULT 0 COMMENT '订单商品件数',
    created_at      DATETIME       NOT NULL COMMENT '创建时间',
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_date (order_date),
    KEY idx_status (order_status),
    KEY idx_customer (customer_id),
    KEY idx_region (region_id)
) ENGINE = InnoDB COMMENT ='订单事实表';

CREATE TABLE fact_order_item
(
    item_id          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    order_id         BIGINT         NOT NULL COMMENT '订单ID',
    product_id       INT            NOT NULL COMMENT '商品ID',
    order_date       DATE           NOT NULL COMMENT '下单日期（冗余，避免明细分析必须 join 订单表）',
    quantity         INT            NOT NULL COMMENT '数量',
    unit_price       DECIMAL(12, 2) NOT NULL COMMENT '成交单价（元）',
    item_amount      DECIMAL(14, 2) NOT NULL COMMENT '明细金额（元）= 数量 × 成交单价',
    item_discount    DECIMAL(14, 2) NOT NULL DEFAULT 0 COMMENT '明细优惠（元）',
    item_pay_amount  DECIMAL(14, 2) NOT NULL COMMENT '明细实付（元）= 明细金额 - 明细优惠',
    PRIMARY KEY (item_id),
    KEY idx_order (order_id),
    KEY idx_product (product_id),
    KEY idx_order_date (order_date)
) ENGINE = InnoDB COMMENT ='订单明细事实表';

-- ------------------------------------------------------------
-- 只读账号：Agent 执行 SQL 一律走这个账号
-- 这是 SqlGuard 之外的第二道防线——即使 AST 校验被绕过，
-- 数据库层面也没有任何写权限。
-- ------------------------------------------------------------
CREATE USER IF NOT EXISTS 'report_ro'@'%' IDENTIFIED BY 'report_ro_pwd';
GRANT SELECT ON report_demo.* TO 'report_ro'@'%';
FLUSH PRIVILEGES;
