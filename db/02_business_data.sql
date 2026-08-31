-- ============================================================
-- 业务库模拟数据生成（report_demo）
--
-- 用确定性伪随机（CRC32 哈希取模）而不是 RAND()：
-- 同一份脚本任何机器跑出来的数据完全一致，评测集的 golden 结果才有意义。
--
-- 数据规模：约 3 万订单 / 9 万明细，覆盖 2026-03-01 ~ 2026-08-31 共 184 天。
-- 生成顺序刻意是「先明细后回写汇总」，保证 fact_order 的金额
-- 与 fact_order_item 严格对得上——否则评测时无法判断是 SQL 错了还是数据本身就不一致。
-- ============================================================

USE report_demo;

SET SESSION cte_max_recursion_depth = 200000;

-- 辅助序列表（用普通表而非临时表：MySQL 不允许同一查询中两次引用同一临时表）
DROP TABLE IF EXISTS tmp_numbers;
CREATE TABLE tmp_numbers
(
    n INT NOT NULL PRIMARY KEY
) ENGINE = InnoDB;

INSERT INTO tmp_numbers (n)
WITH RECURSIVE s AS (SELECT 1 AS n
                     UNION ALL
                     SELECT n + 1 FROM s WHERE n < 100000)
SELECT n FROM s;

-- ------------------------------------------------------------
-- 1. dim_date：2025-01-01 ~ 2026-12-31
-- ------------------------------------------------------------
INSERT INTO dim_date (date_key, year_num, quarter_num, month_num, year_month_str, day_of_week, is_weekend)
SELECT d.dk,
       YEAR(d.dk),
       QUARTER(d.dk),
       MONTH(d.dk),
       DATE_FORMAT(d.dk, '%Y-%m'),
       WEEKDAY(d.dk) + 1,
       IF(WEEKDAY(d.dk) >= 5, 1, 0)
FROM (SELECT DATE_ADD('2025-01-01', INTERVAL n - 1 DAY) AS dk
      FROM tmp_numbers
      WHERE n <= 730) d;

-- ------------------------------------------------------------
-- 2. dim_region：7 大区，共 28 个城市
-- ------------------------------------------------------------
INSERT INTO dim_region (region_name, province, city)
VALUES ('华东', '江苏省', '南京'),
       ('华东', '江苏省', '苏州'),
       ('华东', '浙江省', '杭州'),
       ('华东', '浙江省', '宁波'),
       ('华东', '上海市', '上海'),
       ('华东', '安徽省', '合肥'),
       ('华北', '北京市', '北京'),
       ('华北', '天津市', '天津'),
       ('华北', '河北省', '石家庄'),
       ('华北', '山西省', '太原'),
       ('华南', '广东省', '广州'),
       ('华南', '广东省', '深圳'),
       ('华南', '广东省', '东莞'),
       ('华南', '福建省', '厦门'),
       ('华南', '海南省', '海口'),
       ('华中', '湖北省', '武汉'),
       ('华中', '湖南省', '长沙'),
       ('华中', '河南省', '郑州'),
       ('华中', '江西省', '南昌'),
       ('西南', '四川省', '成都'),
       ('西南', '重庆市', '重庆'),
       ('西南', '云南省', '昆明'),
       ('西南', '贵州省', '贵阳'),
       ('西北', '陕西省', '西安'),
       ('西北', '甘肃省', '兰州'),
       ('西北', '新疆', '乌鲁木齐'),
       ('东北', '辽宁省', '沈阳'),
       ('东北', '吉林省', '长春');

-- ------------------------------------------------------------
-- 3. dim_product：200 个商品，5 个一级类目 × 3 个二级类目
-- ------------------------------------------------------------
INSERT INTO dim_product (product_code, product_name, category_l1, category_l2, brand, unit_cost, list_price, status)
SELECT CONCAT('P', LPAD(n, 5, '0')),
       CONCAT(br.brand_name, ' ', c.l2, ' ', LPAD(n, 3, '0'), ' 型'),
       c.l1,
       c.l2,
       br.brand_name,
       ROUND(base.p * 0.55, 2),
       base.p,
       -- 后 8% 的商品设为下架：制造「在售商品」这个必须由语义层说明的过滤条件
       IF(n > 184, 'off_shelf', 'on_sale')
FROM tmp_numbers t
         JOIN LATERAL (SELECT ELT(CRC32(CONCAT(t.n, 'cat')) % 15 + 1,
                                  '手机数码', '手机数码', '手机数码',
                                  '家用电器', '家用电器', '家用电器',
                                  '服饰鞋包', '服饰鞋包', '服饰鞋包',
                                  '食品生鲜', '食品生鲜', '食品生鲜',
                                  '美妆个护', '美妆个护', '美妆个护') AS l1,
                              ELT(CRC32(CONCAT(t.n, 'cat')) % 15 + 1,
                                  '手机', '平板电脑', '智能穿戴',
                                  '冰箱', '洗衣机', '空调',
                                  '男装', '女装', '箱包',
                                  '休闲零食', '粮油调味', '生鲜果蔬',
                                  '护肤', '彩妆', '洗护') AS l2) c
         JOIN LATERAL (SELECT ELT(CRC32(CONCAT(t.n, 'brand')) % 10 + 1,
                                  'startech', '云禾', 'Lumina', '万嘉', '青柚',
                                  'NORDIC', '知行', 'Vela', '和光', '木野') AS brand_name) br
         JOIN LATERAL (SELECT ROUND(19.9 + (CRC32(CONCAT(t.n, 'price')) % 498000) / 100.0, 2) AS p) base
WHERE t.n <= 200;

-- ------------------------------------------------------------
-- 4. dim_customer：5000 个客户
-- ------------------------------------------------------------
INSERT INTO dim_customer (customer_code, customer_name, customer_level, register_date, region_id, channel)
SELECT CONCAT('C', LPAD(n, 6, '0')),
       CONCAT('客户', LPAD(n, 6, '0')),
       -- 等级分布：普通 55% / 银卡 25% / 金卡 15% / 铂金 5%
       CASE
           WHEN CRC32(CONCAT(n, 'lvl')) % 100 < 55 THEN 'normal'
           WHEN CRC32(CONCAT(n, 'lvl')) % 100 < 80 THEN 'silver'
           WHEN CRC32(CONCAT(n, 'lvl')) % 100 < 95 THEN 'gold'
           ELSE 'platinum'
           END,
       DATE_ADD('2025-01-01', INTERVAL CRC32(CONCAT(n, 'reg')) % 600 DAY),
       CRC32(CONCAT(n, 'rgn')) % 28 + 1,
       ELT(CRC32(CONCAT(n, 'chn')) % 4 + 1, 'app', 'web', 'mini_program', 'offline')
FROM tmp_numbers
WHERE n <= 5000;

-- ------------------------------------------------------------
-- 5. fact_order：30000 笔订单，2026-03-01 ~ 2026-08-31
--    金额先置 0，等明细生成后回写，保证主子表严格一致
-- ------------------------------------------------------------
INSERT INTO fact_order (order_no, customer_id, region_id, order_date, order_status, channel,
                        order_amount, discount_amount, freight_amount, pay_amount, refund_amount,
                        item_count, created_at)
SELECT CONCAT('SO', DATE_FORMAT(od.d, '%Y%m%d'), LPAD(t.n, 6, '0')),
       CRC32(CONCAT(t.n, 'cust')) % 5000 + 1,
       CRC32(CONCAT(t.n, 'rgn')) % 28 + 1,
       od.d,
       -- 状态分布：已完成 55% / 已发货 12% / 已支付 10% / 待支付 8% / 已取消 9% / 已退款 6%
       CASE
           WHEN CRC32(CONCAT(t.n, 'st')) % 100 < 55 THEN 'completed'
           WHEN CRC32(CONCAT(t.n, 'st')) % 100 < 67 THEN 'shipped'
           WHEN CRC32(CONCAT(t.n, 'st')) % 100 < 77 THEN 'paid'
           WHEN CRC32(CONCAT(t.n, 'st')) % 100 < 85 THEN 'created'
           WHEN CRC32(CONCAT(t.n, 'st')) % 100 < 94 THEN 'cancelled'
           ELSE 'refunded'
           END,
       ELT(CRC32(CONCAT(t.n, 'chn')) % 4 + 1, 'app', 'web', 'mini_program', 'offline'),
       0, 0, 0, 0, 0,
       CRC32(CONCAT(t.n, 'cnt')) % 4 + 1,
       TIMESTAMP(od.d, SEC_TO_TIME(CRC32(CONCAT(t.n, 'sec')) % 86400))
FROM tmp_numbers t
         JOIN LATERAL (SELECT DATE_ADD('2026-03-01', INTERVAL CRC32(CONCAT(t.n, 'day')) % 184 DAY) AS d) od
WHERE t.n <= 30000;

-- ------------------------------------------------------------
-- 6. fact_order_item：按订单的 item_count 展开，约 7.5 万行
--    月度系数制造轻微季节性，让「环比/同比」类问题有真实信号
-- ------------------------------------------------------------
INSERT INTO fact_order_item (order_id, product_id, order_date, quantity, unit_price,
                             item_amount, item_discount, item_pay_amount)
SELECT o.order_id,
       p.product_id,
       o.order_date,
       q.qty,
       pr.price,
       ROUND(q.qty * pr.price, 2),
       dc.disc,
       ROUND(q.qty * pr.price - dc.disc, 2)
FROM fact_order o
         JOIN tmp_numbers seq ON seq.n <= o.item_count
         JOIN dim_product p
              ON p.product_id = CRC32(CONCAT(o.order_id, '-', seq.n, 'prod')) % 200 + 1
         JOIN LATERAL (SELECT CRC32(CONCAT(o.order_id, '-', seq.n, 'qty')) % 3 + 1 AS qty) q
    -- 成交价 = 标价 × (0.75 ~ 1.0) × 月度系数
         JOIN LATERAL (SELECT ROUND(p.list_price
                                        * (0.75 + (CRC32(CONCAT(o.order_id, '-', seq.n, 'dp')) % 26) / 100.0)
                                        * ELT(MONTH(o.order_date) - 2, 0.92, 0.96, 1.00, 1.05, 1.03, 1.12),
                                    2) AS price) pr
    -- 明细优惠：30% 的明细有 5~15% 折扣
         JOIN LATERAL (SELECT IF(CRC32(CONCAT(o.order_id, '-', seq.n, 'dsc')) % 100 < 30,
                                 ROUND(q.qty * pr.price
                                           * (5 + CRC32(CONCAT(o.order_id, '-', seq.n, 'dr')) % 11) / 100.0, 2),
                                 0.00) AS disc) dc;

-- ------------------------------------------------------------
-- 7. 回写 fact_order 汇总金额
--    运费规则：实付满 99 免运费，否则 10 元（这条业务规则同样无法从 DDL 推断）
-- ------------------------------------------------------------
UPDATE fact_order o
    JOIN (SELECT order_id,
                 SUM(item_amount)     AS amt,
                 SUM(item_discount)   AS disc,
                 SUM(quantity)        AS qty
          FROM fact_order_item
          GROUP BY order_id) i ON i.order_id = o.order_id
SET o.order_amount    = i.amt,
    o.discount_amount = i.disc,
    o.item_count      = i.qty,
    o.freight_amount  = IF(i.amt - i.disc >= 99, 0.00, 10.00);

-- 实付：未支付/已取消为 0；其余为 应付 - 优惠 + 运费
UPDATE fact_order
SET pay_amount = IF(order_status IN ('created', 'cancelled'),
                    0.00,
                    ROUND(order_amount - discount_amount + freight_amount, 2));

-- 退款：仅 refunded 状态，全额退
UPDATE fact_order
SET refund_amount = pay_amount
WHERE order_status = 'refunded';

DROP TABLE tmp_numbers;

-- ------------------------------------------------------------
-- 生成结果自检
-- ------------------------------------------------------------
SELECT 'dim_date' AS t, COUNT(*) AS rows_cnt FROM dim_date
UNION ALL SELECT 'dim_region', COUNT(*) FROM dim_region
UNION ALL SELECT 'dim_product', COUNT(*) FROM dim_product
UNION ALL SELECT 'dim_customer', COUNT(*) FROM dim_customer
UNION ALL SELECT 'fact_order', COUNT(*) FROM fact_order
UNION ALL SELECT 'fact_order_item', COUNT(*) FROM fact_order_item;

-- 主子表金额一致性校验：应返回 0 行
SELECT o.order_id, o.order_amount, i.amt
FROM fact_order o
         JOIN (SELECT order_id, ROUND(SUM(item_amount), 2) AS amt
               FROM fact_order_item GROUP BY order_id) i ON i.order_id = o.order_id
WHERE ABS(o.order_amount - i.amt) > 0.01
LIMIT 5;
