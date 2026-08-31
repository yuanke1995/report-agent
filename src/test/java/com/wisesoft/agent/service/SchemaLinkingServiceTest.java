package com.wisesoft.agent.service;

import com.wisesoft.agent.config.AgentProperties;
import com.wisesoft.agent.semantic.GoldenExample;
import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.semantic.SemanticModelLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema Linking 召回测试：选对表是 NL2SQL 准确率的第一大变量，
 * 召回逻辑必须可验证、可回归。
 *
 * @author yuanke
 */
class SchemaLinkingServiceTest {

    private static SchemaLinkingService linking;

    @BeforeAll
    static void setUp() {
        SemanticModel model = new SemanticModelLoader().load();
        AgentProperties props = new AgentProperties();
        ConfigService config = new ConfigService(null, null, null) {
            @Override
            public int getInt(String key, int def) {
                return def;
            }
        };
        linking = new SchemaLinkingService(model, config);
    }

    private SchemaLinkingService.LinkingResult link(String q) {
        return linking.link(q);
    }

    @Test
    @DisplayName("「销售额」同义词命中 gmv 指标，主表 fact_order 优先")
    void salesSynonymPicksOrderTable() {
        SchemaLinkingService.LinkingResult r = link("上个月销售额是多少");
        assertTrue(r.tables().contains("fact_order"), "gmv 的 baseTable 必须入选: " + r.tables());
        assertTrue(r.metricNames().contains("gmv"), "指标 gmv 必须被识别");
        assertTrue(r.tables().indexOf("fact_order") <= r.tables().indexOf("dim_region"),
                "事实表应排在维度表前面");
    }

    @Test
    @DisplayName("区域名命中地区表")
    void regionNamePicksRegionTable() {
        SchemaLinkingService.LinkingResult r = link("华南区的销售额");
        assertTrue(r.tables().contains("dim_region"), "区域名必须召回地区表");
    }

    @Test
    @DisplayName("商品类目问题召回明细表和商品表，且命中 golden 示例")
    void categoryQuestionPicksItemAndProduct() {
        SchemaLinkingService.LinkingResult r = link("食品生鲜类目销量最高的商品");
        assertTrue(r.tables().contains("fact_order_item"), "销量在明细表");
        assertTrue(r.tables().contains("dim_product"), "类目在商品表");
        assertTrue(r.tables().contains("fact_order"), "状态过滤需要订单表");
        assertFalse(r.goldenExamples().isEmpty(), "应召回 golden 示例");
        assertTrue(r.goldenExamples().stream().anyMatch(e -> "category_top".equals(e.getId())),
                "应命中类目排行示例");
    }

    @Test
    @DisplayName("客户等级问题召回客户表")
    void customerLevelPicksCustomerTable() {
        SchemaLinkingService.LinkingResult r = link("金卡和铂金客户的消费总额");
        assertTrue(r.tables().contains("dim_customer"), "客户等级在客户表");
        assertTrue(r.tables().contains("fact_order"), "消费总额在订单表");
        assertTrue(r.goldenExamples().stream().anyMatch(e -> "high_value_customers".equals(e.getId())),
                "应命中高价值客户示例");
    }

    @Test
    @DisplayName("「退款率」关键词命中指标，含退款口径的表正确")
    void refundRateHitsMetric() {
        SchemaLinkingService.LinkingResult r = link("最近三个月的退款率走势");
        assertTrue(r.metricNames().contains("refund_rate"), "退款率指标必须被识别");
        assertTrue(r.tables().contains("fact_order"));
    }

    @Test
    @DisplayName("客户存量问题不误拉订单表")
    void customerInventoryDoesNotPullOrderTable() {
        SchemaLinkingService.LinkingResult r = link("铂金客户有多少人");
        assertTrue(r.tables().contains("dim_customer"), "客户表必须入选");
        assertTrue(r.tables().indexOf("dim_customer") < r.tables().indexOf("fact_order"),
                "客户表应排在订单表前面");
    }

    @Test
    @DisplayName("golden 示例按关键词命中数排序，命中多的排前面")
    void goldenOrderedByHits() {
        SchemaLinkingService.LinkingResult r = link("金卡客户的消费总额");
        List<GoldenExample> goldens = r.goldenExamples();
        assertFalse(goldens.isEmpty());
        // 命中 2 个关键词（金卡+消费）的 high_value_customers 应排最前
        assertEquals("high_value_customers", goldens.get(0).getId());
    }

    @Test
    @DisplayName("无业务含义的输入不产生幻觉召回")
    void garbageInputProducesNothing() {
        SchemaLinkingService.LinkingResult r = link("你好");
        assertTrue(r.tables().isEmpty(), "无业务词时不应选表: " + r.tables());
    }

    @Test
    @DisplayName("空输入安全返回")
    void blankInputSafe() {
        SchemaLinkingService.LinkingResult r = link("  ");
        assertTrue(r.isEmpty());
    }
}
