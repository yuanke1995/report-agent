package com.wisesoft.agent.sql;

import com.wisesoft.agent.semantic.ReportTemplate;
import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.semantic.SemanticModelLoader;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 报表模板 SQL 的真实执行测试。
 * <p>
 * 模板 SQL 是人工编写的，它的正确性是整条"模板优先"路径的地基——
 * 模板算错了，模型抽参数抽得再准也没用。所以这里真跑一遍，
 * 并且交叉验证：模板算出来的销售额，要和直接按 metrics.yml 口径算的对得上。
 * <p>
 * 依赖本地 report_demo 库；库不可用时跳过而不是失败，
 * 免得在没有数据库的 CI 上误报。
 *
 * @author yuanke
 */
class ReportTemplateSqlTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/report_demo?useUnicode=true&useSSL=false"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    private static SemanticModel model;
    private static HikariDataSource ds;
    private static NamedParameterJdbcTemplate jdbc;
    private static TemplateParamValidator validator;

    @BeforeAll
    static void setUp() {
        model = new SemanticModelLoader().load();
        validator = new TemplateParamValidator();
        try {
            HikariDataSource d = new HikariDataSource();
            d.setJdbcUrl(URL);
            d.setUsername("report_ro");
            d.setPassword("report_ro_pwd");
            d.setReadOnly(true);
            d.setConnectionTimeout(3000);
            d.setMaximumPoolSize(2);
            d.getConnection().close();
            ds = d;
            jdbc = new NamedParameterJdbcTemplate(d);
        } catch (Exception e) {
            ds = null;
        }
        Assumptions.assumeTrue(ds != null,
                "本地 report_demo 库不可用，跳过（先执行 db/01_business_schema.sql 与 db/02_business_data.sql）");
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    private List<Map<String, Object>> run(String templateId, Map<String, String> raw) {
        ReportTemplate t = model.template(templateId);
        assertNotNull(t, "模板不存在: " + templateId);
        Map<String, Object> bound = validator.validate(t, raw);
        return jdbc.queryForList(t.getSql(), new MapSqlParameterSource(bound));
    }

    @Test
    @DisplayName("月度销售汇总：6 个月各一行，客单价 = 销售额 / 订单量")
    void monthlySummary() {
        List<Map<String, Object>> rows = run("sales_monthly_summary",
                Map.of("startMonth", "2026-03", "endMonth", "2026-08"));

        assertEquals(6, rows.size(), "3 月到 8 月应有 6 行");
        assertEquals(List.of("2026-03", "2026-04", "2026-05", "2026-06", "2026-07", "2026-08"),
                rows.stream().map(r -> String.valueOf(r.get("月份"))).toList(),
                "应按月份升序");

        for (Map<String, Object> r : rows) {
            double gmv = num(r.get("销售额"));
            double orders = num(r.get("订单量"));
            double aov = num(r.get("客单价"));
            assertTrue(gmv > 0, "销售额应为正");
            assertTrue(orders > 0, "订单量应为正");
            assertEquals(gmv / orders, aov, 0.01, "客单价必须等于销售额除以订单量");
            assertTrue(num(r.get("下单客户数")) <= orders, "下单客户数不可能多于订单量");
        }
    }

    @Test
    @DisplayName("月度汇总的口径与 metrics.yml 的 gmv 定义完全一致")
    void monthlySummaryMatchesMetricDefinition() {
        List<Map<String, Object>> rows = run("sales_monthly_summary",
                Map.of("startMonth", "2026-08", "endMonth", "2026-08"));
        double fromTemplate = num(rows.get(0).get("销售额"));

        // 直接按 metrics.yml 的 gmv 口径算一遍：SUM(pay_amount) + 三状态过滤
        Double fromMetric = jdbc.queryForObject("""
                SELECT ROUND(SUM(fact_order.pay_amount), 2)
                FROM fact_order
                WHERE fact_order.order_status IN ('paid', 'shipped', 'completed')
                  AND DATE_FORMAT(fact_order.order_date, '%Y-%m') = '2026-08'
                """, new MapSqlParameterSource(), Double.class);

        assertEquals(fromMetric, fromTemplate, 0.01,
                "模板口径与指标定义必须一致，否则同一个问题走两条路会得到两个数");
    }

    @Test
    @DisplayName("已取消与待支付订单不计入销售额")
    void excludesUnpaidOrders() {
        Integer unpaid = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fact_order WHERE order_status IN ('created','cancelled')",
                new MapSqlParameterSource(), Integer.class);
        assertNotNull(unpaid);
        assertTrue(unpaid > 0, "测试数据里应当存在未支付订单，否则这条断言没有意义");

        Double unpaidAmount = jdbc.queryForObject(
                "SELECT SUM(pay_amount) FROM fact_order WHERE order_status IN ('created','cancelled')",
                new MapSqlParameterSource(), Double.class);
        assertEquals(0.0, unpaidAmount, 0.001, "未支付订单的实付金额应为 0");
    }

    @Test
    @DisplayName("区域对比：7 个大区，按销售额降序")
    void regionComparison() {
        List<Map<String, Object>> rows = run("sales_by_region",
                Map.of("startDate", "2026-03-01", "endDate", "2026-08-31"));

        assertEquals(7, rows.size(), "应覆盖 7 个大区");
        double prev = Double.MAX_VALUE;
        for (Map<String, Object> r : rows) {
            double gmv = num(r.get("销售额"));
            assertTrue(gmv <= prev, "必须按销售额降序");
            prev = gmv;
        }
        assertTrue(rows.stream().map(r -> String.valueOf(r.get("大区")))
                .toList().containsAll(List.of("华东", "华南", "华北")));
    }

    @Test
    @DisplayName("商品排行：默认取 10 条，销售额降序，毛利小于销售额")
    void productTopN() {
        List<Map<String, Object>> rows = run("product_top_n",
                Map.of("startDate", "2026-08-01", "endDate", "2026-08-31"));

        assertEquals(10, rows.size(), "topN 默认值应为 10");
        double prev = Double.MAX_VALUE;
        for (Map<String, Object> r : rows) {
            double sales = num(r.get("销售额"));
            assertTrue(sales <= prev, "必须按销售额降序");
            prev = sales;
            assertTrue(num(r.get("毛利")) < sales, "毛利必须小于销售额（成本为正）");
            assertTrue(num(r.get("销量")) > 0);
        }
    }

    @Test
    @DisplayName("商品排行的类目筛选生效")
    void productTopNWithCategory() {
        Map<String, String> params = new HashMap<>();
        params.put("startDate", "2026-03-01");
        params.put("endDate", "2026-08-31");
        params.put("topN", "5");
        params.put("categoryL1", "手机数码");

        List<Map<String, Object>> rows = run("product_top_n", params);
        assertEquals(5, rows.size());
        for (Map<String, Object> r : rows) {
            assertEquals("手机数码", r.get("一级类目"), "类目筛选未生效");
        }
    }

    @Test
    @DisplayName("商品维度销售额用明细实付，不能用订单表 pay_amount（含运费且无法拆到商品）")
    void productSalesUsesItemPayAmount() {
        Double byItem = jdbc.queryForObject("""
                SELECT ROUND(SUM(i.item_pay_amount), 2)
                FROM fact_order_item i
                         INNER JOIN fact_order o ON o.order_id = i.order_id
                WHERE o.order_status IN ('paid', 'shipped', 'completed')
                  AND i.order_date BETWEEN '2026-08-01' AND '2026-08-31'
                """, new MapSqlParameterSource(), Double.class);

        Double byOrder = jdbc.queryForObject("""
                SELECT ROUND(SUM(o.pay_amount), 2)
                FROM fact_order o
                WHERE o.order_status IN ('paid', 'shipped', 'completed')
                  AND o.order_date BETWEEN '2026-08-01' AND '2026-08-31'
                """, new MapSqlParameterSource(), Double.class);

        assertNotNull(byItem);
        assertNotNull(byOrder);
        // 差额就是运费，订单口径必然更大。这个差值证明了两个口径确实不能混用。
        assertTrue(byOrder > byItem,
                "订单实付含运费，应大于明细实付之和；两者相等说明数据生成有问题");
    }

    @Test
    @DisplayName("只读账号无法执行写操作——SqlGuard 之外的第二道防线")
    void readOnlyAccountBlocksWrites() {
        assertThrows(Exception.class,
                () -> jdbc.update("DELETE FROM fact_order WHERE order_id = -1", new MapSqlParameterSource()),
                "只读账号执行 DELETE 必须失败");
    }

    private double num(Object o) {
        assertNotNull(o, "数值列不应为 null");
        return ((Number) o).doubleValue();
    }
}
