package com.wisesoft.agent.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语义层加载与查询的单元测试（不依赖数据库）。
 * <p>
 * 数据库对齐校验由 {@link SemanticModelValidator} 在启动时执行，
 * 这里只覆盖纯解析与索引逻辑。
 *
 * @author yuanke
 */
class SemanticModelLoaderTest {

    private static final SemanticModel MODEL = new SemanticModelLoader().load();

    @Test
    @DisplayName("语义层能完整加载")
    void loads() {
        assertEquals(6, MODEL.getTables().size(), "表数量");
        assertEquals(13, MODEL.getMetrics().size(), "指标数量");
        assertEquals(7, MODEL.getJoins().size(), "join 路径数量");
        assertEquals(3, MODEL.getTemplates().size(), "报表模板数量");
        assertFalse(MODEL.getForbiddenJoins().isEmpty(), "应有禁止连接规则");
    }

    @Test
    @DisplayName("指标携带口径边界——这是语义层存在的主要理由")
    void metricsCarryFilters() {
        MetricDef gmv = MODEL.metric("gmv");
        assertNotNull(gmv);
        assertEquals(List.of("fact_order.order_status IN ('paid', 'shipped', 'completed')"),
                gmv.getRequiredFilters(),
                "销售额必须排除未支付/已取消/已退款订单");
        assertFalse(gmv.getCaveats().isEmpty(), "销售额存在含不含退款的口径分歧，必须记录");

        // gmv 与 gross_gmv 的差别就在口径边界上，表达式完全相同
        MetricDef gross = MODEL.metric("gross_gmv");
        assertEquals(gmv.getExpression(), gross.getExpression());
        assertNotEquals(gmv.getRequiredFilters(), gross.getRequiredFilters());
    }

    @Test
    @DisplayName("同义词能解析到指标：中文提问的落点")
    void resolvesMetricSynonyms() {
        for (String term : List.of("销售额", "GMV", "成交额", "营业额", "流水")) {
            assertEquals("gmv", MODEL.resolveMetric(term).getName(), "同义词: " + term);
        }
        assertEquals("avg_order_value", MODEL.resolveMetric("客单价").getName());
        assertEquals("arpu", MODEL.resolveMetric("人均消费").getName());
        assertNull(MODEL.resolveMetric("不存在的指标"));
    }

    @Test
    @DisplayName("同义词能解析到表")
    void resolvesTableSynonyms() {
        assertEquals("fact_order", MODEL.resolveTable("订单"));
        assertEquals("fact_order", MODEL.resolveTable("订单表"));
        assertEquals("fact_order_item", MODEL.resolveTable("销售明细"));
        assertEquals("dim_product", MODEL.resolveTable("SKU"));
        assertEquals("dim_region", MODEL.resolveTable("地区"));
    }

    @Test
    @DisplayName("join 白名单不受书写顺序影响")
    void joinLookupIsOrderInsensitive() {
        JoinDef expected = MODEL.joinById("fact_order__dim_region");
        assertNotNull(expected);

        // 同一条连接的四种等价写法都应命中同一条白名单记录
        assertSame(expected, MODEL.approvedJoin("fact_order.region_id = dim_region.region_id"));
        assertSame(expected, MODEL.approvedJoin("dim_region.region_id = fact_order.region_id"));
        assertSame(expected, MODEL.approvedJoin("fact_order.region_id=dim_region.region_id"));
        assertSame(expected, MODEL.approvedJoin("  FACT_ORDER.REGION_ID  =  DIM_REGION.REGION_ID  "));

        // 编造的连接不在白名单里
        assertNull(MODEL.approvedJoin("fact_order.customer_id = dim_product.product_id"));
    }

    @Test
    @DisplayName("禁止连接可查，且带可解释的原因")
    void forbiddenJoinsAreExplainable() {
        ForbiddenJoin f = MODEL.forbiddenJoin("fact_order", "dim_product");
        assertNotNull(f, "订单表直连商品表应被禁止");
        assertTrue(f.getReason().contains("fact_order_item"), "原因里要给出正确路径");

        // 方向反过来也要命中
        assertNotNull(MODEL.forbiddenJoin("dim_product", "fact_order"));
        assertNull(MODEL.forbiddenJoin("fact_order", "dim_region"), "已批准的连接不该在黑名单里");
    }

    @Test
    @DisplayName("枚举值中文映射齐全：英文存储、中文提问的翻译依据")
    void enumsAreMapped() {
        ColumnDef status = MODEL.table("fact_order").column("order_status");
        assertTrue(status.hasEnums());
        assertEquals("已支付", status.getEnums().get("paid"));
        assertEquals("已退款", status.getEnums().get("refunded"));
        assertEquals(6, status.getEnums().size());

        ColumnDef level = MODEL.table("dim_customer").column("customer_level");
        assertEquals("铂金", level.getEnums().get("platinum"));
    }

    @Test
    @DisplayName("每张表最多一个主时间列")
    void primaryTimeColumnIsUnique() {
        for (TableDef t : MODEL.getTables().values()) {
            long count = t.getColumns().stream().filter(ColumnDef::isPrimaryTimeColumn).count();
            assertTrue(count <= 1, t.getTable() + " 声明了多个主时间列");
        }
        assertEquals("order_date", MODEL.table("fact_order").primaryTimeColumn().getName());
    }

    @Test
    @DisplayName("模板参数声明与 SQL 占位符一一对应")
    void templateParamsMatchSql() {
        ReportTemplate t = MODEL.template("product_top_n");
        assertNotNull(t);
        for (TemplateParam p : t.getParams()) {
            assertTrue(t.getSql().contains(":" + p.getName()),
                    "声明的参数 " + p.getName() + " 未在 SQL 中使用");
        }
        assertEquals(TemplateParam.ParamType.ENUM, t.param("categoryL1").getType());
        assertFalse(t.param("categoryL1").getOptions().isEmpty());
        assertEquals(TemplateParam.ParamType.INT, t.param("topN").getType());
    }
}
