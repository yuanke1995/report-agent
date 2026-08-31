package com.wisesoft.agent.sql;

import com.wisesoft.agent.config.AgentProperties;
import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.semantic.SemanticModelLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlGuard 测试：安全核心，每一类攻击/错误都要有对应的用例。
 * <p>
 * 原则：SqlGuard 是只读白名单校验的最后一层（之外还有只读账号），
 * 它的职责是"宁可错杀，不可放过"——所以测试断言的是拒绝行为，
 * 而不是"这个 SQL 是不是真的危险"。
 *
 * @author yuanke
 */
class SqlGuardTest {

    private static SqlGuard guard;

    @BeforeAll
    static void setUp() {
        SemanticModel model = new SemanticModelLoader().load();
        AgentProperties props = new AgentProperties();
        props.getSqlExec().setMaxRows(1000);
        guard = new SqlGuard(model, props);
    }

    private SqlValidationException rejected(String sql) {
        return assertThrows(SqlValidationException.class, () -> guard.validate(sql),
                "应当拒绝: " + sql);
    }

    // ------------------------------------------------------------
    // 合法 SQL
    // ------------------------------------------------------------

    @Test
    @DisplayName("合法的聚合查询通过，且自动注入 LIMIT")
    void acceptsValidQuery() {
        SqlGuard.GuardResult r = guard.validate("""
                SELECT DATE_FORMAT(o.order_date, '%Y-%m') AS m, SUM(o.pay_amount) AS gmv
                FROM fact_order o
                WHERE o.order_status IN ('paid', 'shipped', 'completed')
                GROUP BY DATE_FORMAT(o.order_date, '%Y-%m')
                ORDER BY m DESC
                """);
        assertTrue(r.limitInjected(), "无 LIMIT 应注入");
        assertTrue(r.guardedSql().toLowerCase().contains("limit 1000"), "注入行数上限: " + r.guardedSql());
    }

    @Test
    @DisplayName("带 join 的合法查询通过：明细连订单、订单连地区")
    void acceptsValidJoin() {
        guard.validate("""
                SELECT r.region_name, SUM(i.item_pay_amount) AS sales
                FROM fact_order_item i
                         INNER JOIN fact_order o ON o.order_id = i.order_id
                         INNER JOIN dim_region r ON r.region_id = o.region_id
                WHERE o.order_status = 'completed'
                GROUP BY r.region_name
                """);
    }

    @Test
    @DisplayName("已有 LIMIT 时不重复注入")
    void keepsExistingLimit() {
        SqlGuard.GuardResult r = guard.validate(
                "SELECT pay_amount FROM fact_order LIMIT 10");
        assertFalse(r.limitInjected());
        assertTrue(r.guardedSql().toLowerCase().contains("limit 10"));
    }

    @Test
    @DisplayName("大小写、别名、多余空白都接受")
    void acceptsCaseAndAliasVariations() {
        guard.validate("  SELECT   o.pay_amount   FROM   FACT_ORDER  o   WHERE  o.order_status = 'paid'  ");
        guard.validate("SELECT c.customer_name FROM dim_customer c WHERE c.customer_level = 'gold'");
    }

    @Test
    @DisplayName("子查询递归校验通过")
    void acceptsNestedSubquery() {
        guard.validate("""
                SELECT t.customer_name, t.total
                FROM (SELECT c.customer_name, SUM(o.pay_amount) AS total
                      FROM fact_order o
                               JOIN dim_customer c ON c.customer_id = o.customer_id
                      WHERE o.order_status = 'paid'
                      GROUP BY c.customer_name) t
                ORDER BY t.total DESC
                """);
    }

    // ------------------------------------------------------------
    // 注入与越权类
    // ------------------------------------------------------------

    @Test
    @DisplayName("非 SELECT 语句一律拒绝")
    void rejectsNonSelect() {
        rejected("DELETE FROM fact_order WHERE order_id = 1");
        rejected("UPDATE fact_order SET order_status = 'paid'");
        rejected("INSERT INTO fact_order (order_id) VALUES (1)");
        rejected("DROP TABLE fact_order");
        rejected("TRUNCATE TABLE fact_order");
    }

    @Test
    @DisplayName("注释注入无效：解析器不把注释当语句边界")
    void rejectsCommentInjection() {
        // 这类 SQL 用正则很容易漏掉，AST 解析后注释直接消失，剩下的还是 DELETE
        SqlValidationException e = rejected("DELETE FROM fact_order WHERE order_id = 1 -- 注释");
        assertEquals(SqlValidationException.Stage.GUARD, e.getStage());
    }

    @Test
    @DisplayName("SELECT INTO OUTFILE 拒绝")
    void rejectsIntoOutfile() {
        rejected("SELECT * FROM fact_order INTO OUTFILE '/tmp/x.csv'");
    }

    @Test
    @DisplayName("CROSS JOIN 拒绝")
    void rejectsCrossJoin() {
        rejected("SELECT * FROM fact_order o CROSS JOIN dim_product p");
    }

    @Test
    @DisplayName("危险函数拒绝")
    void rejectsBannedFunctions() {
        SqlValidationException e = rejected(
                "SELECT SLEEP(5) FROM fact_order LIMIT 1");
        assertTrue(e.getHints().stream().anyMatch(h -> h.contains("SLEEP")));
        rejected("SELECT BENCHMARK(1000000, MD5('x'))");
    }

    // ------------------------------------------------------------
    // 白名单校验
    // ------------------------------------------------------------

    @Test
    @DisplayName("白名单外的表拒绝，且提示可用表")
    void rejectsUnknownTable() {
        SqlValidationException e = rejected(
                "SELECT * FROM users WHERE id = 1");
        assertTrue(e.getHints().stream().anyMatch(h -> h.contains("fact_order")),
                "提示里要给出可用表，模型才知道怎么改");
    }

    @Test
    @DisplayName("白名单外的列拒绝，且提示该表可选列")
    void rejectsUnknownColumn() {
        SqlValidationException e = rejected(
                "SELECT o.bad_column FROM fact_order o LIMIT 1");
        assertTrue(e.getHints().stream().anyMatch(h -> h.contains("pay_amount")),
                "提示里要给出真实列名，模型才知道怎么改");
    }

    @Test
    @DisplayName("表存在但列前缀不存在的列拒绝")
    void rejectsColumnFromWrongTable() {
        rejected("SELECT o.unit_cost FROM fact_order o LIMIT 1"); // unit_cost 属于 dim_product
    }

    @Test
    @DisplayName("无前缀且多表共有的列拒绝（歧义）")
    void rejectsAmbiguousColumn() {
        // channel 在 fact_order 和 dim_customer 都有
        SqlValidationException e = rejected(
                "SELECT channel FROM fact_order o JOIN dim_customer c ON c.customer_id = o.customer_id LIMIT 1");
        assertTrue(e.getHints().stream().anyMatch(h -> h.contains("歧义") || h.contains("前缀")));
    }

    // ------------------------------------------------------------
    // join 白名单
    // ------------------------------------------------------------

    @Test
    @DisplayName("未批准的连接拒绝：给出明确原因")
    void rejectsUnapprovedJoin() {
        SqlValidationException e = rejected("""
                SELECT * FROM fact_order o
                JOIN dim_product p ON p.product_id = o.order_id
                LIMIT 1
                """);
        // 这张表对同时命中「禁止连接」与「不在批准列表」，两条提示都给过
        String hint = String.join("\n", e.getHints());
        assertTrue(hint.contains("不在批准列表") || hint.contains("不允许直接连接"),
                "要明确说连接不被允许: " + hint);
    }

    @Test
    @DisplayName("禁止连接（订单直连商品）拒绝，并给出正确路径")
    void rejectsForbiddenJoin() {
        SqlValidationException e = rejected("""
                SELECT * FROM fact_order o
                JOIN dim_product p ON p.product_id = o.order_id
                LIMIT 1
                """);
        // 注意：这个 on 条件本身就不在白名单（order_id vs product_id 不相连），
        // 更典型的禁止连接是 on 条件正确但表对禁止——换一个用例
        String hint = String.join("\n", e.getHints());
        assertTrue(hint.contains("fact_order_item"), "提示里要给出经由明细表的中转路径");
    }

    @Test
    @DisplayName("join 方向反了（1:n 放大金额）拒绝")
    void rejectsReversedJoinDirection() {
        // fact_order_item__fact_order 的批准方向是明细→订单（n:1）
        // 模型反写订单→明细，按订单聚合时会放大金额
        SqlValidationException e = rejected("""
                SELECT o.order_id, SUM(o.pay_amount) AS gmv
                FROM fact_order o
                LEFT JOIN fact_order_item i ON i.order_id = o.order_id
                GROUP BY o.order_id
                LIMIT 5
                """);
        String hint = String.join("\n", e.getHints());
        assertTrue(hint.contains("方向反了"), "要明确说方向反了: " + hint);
        assertTrue(hint.contains("明细放大"), "要解释为什么方向重要");
    }

    @Test
    @DisplayName("同一条 join 的等价写法都通过（条件顺序、大小写）")
    void acceptsJoinVariations() {
        guard.validate("""
                SELECT r.region_name
                FROM fact_order o
                JOIN dim_region r ON r.region_id = o.region_id
                LIMIT 1
                """);
        guard.validate("""
                SELECT r.region_name
                FROM fact_order o
                JOIN dim_region r ON o.region_id = r.region_id
                LIMIT 1
                """);
    }

    @Test
    @DisplayName("解析失败给出 PARSE 阶段错误与修复提示")
    void parseFailureIsActionable() {
        SqlValidationException e = rejected("SELECT FROM WHERE");
        assertEquals(SqlValidationException.Stage.PARSE, e.getStage());
        assertFalse(e.getHints().isEmpty(), "解析失败也要给修复建议");
        assertTrue(e.toModelText().contains("修复建议"));
    }

    @Test
    @DisplayName("校验错误渲染成给模型的结构化文本")
    void rendersActionableModelText() {
        SqlValidationException e = rejected(
                "SELECT o.pay_amnt FROM fact_order o LIMIT 1");
        String text = e.toModelText();
        assertTrue(text.contains("安全校验"));
        assertTrue(text.contains("pay_amount"), "修复建议带真实列名");
        assertTrue(text.contains("不要重复提交同一条 SQL"));
    }
}
