package com.wisesoft.agent.tools;

import com.wisesoft.agent.config.AgentProperties;
import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.semantic.SemanticModelLoader;
import com.wisesoft.agent.service.ConfigService;
import com.wisesoft.agent.sql.SqlGuard;
import com.wisesoft.agent.service.SemanticPromptBuilder;
import com.wisesoft.agent.sql.TemplateParamValidator;
import com.wisesoft.agent.support.SemanticTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具注册测试：验证 Spring AI 能从 {@code @Tool} 注解正确提取出工具定义。
 * <p>
 * 这一步不需要模型也能测，而它覆盖了 tool calling 一半的失败面：
 * 工具名对不对、描述有没有被读到、参数 schema 生成得对不对、必填标记有没有生效。
 * 剩下一半（模型会不会在正确的时机选对工具）才需要真实模型验证。
 * <p>
 * 顺带守住一条纪律：工具描述必须写清"什么时候用"。模型看不到方法体，
 * 只能看到 description，描述含糊是 tool calling 最常见的失败原因。
 *
 * @author yuanke
 */
class ReportToolsRegistrationTest {

    private static final SemanticModel MODEL = new SemanticModelLoader().load();

    private ReportTools tools() {
        AgentProperties props = new AgentProperties();
        ConfigService config = new ConfigService(null, null, null) {
            @Override
            public int getInt(String key, int def) {
                return def;
            }

            @Override
            public boolean getBoolean(String key) {
                return true;
            }
        };
        SqlGuard guard = new SqlGuard(MODEL, props);
        return new ReportTools(MODEL, new SemanticPromptBuilder(MODEL), null,
                new TemplateParamValidator(), guard, config);
    }

    private Map<String, ToolDefinition> definitions() {
        return Arrays.stream(ToolCallbacks.from(tools()))
                .map(ToolCallback::getToolDefinition)
                .collect(Collectors.toMap(ToolDefinition::name, d -> d));
    }

    @Test
    @DisplayName("五个工具全部被 Spring AI 识别")
    void allToolsRegistered() {
        Map<String, ToolDefinition> defs = definitions();
        assertEquals(
                List.of("ask_clarification", "execute_sql", "get_table_schema",
                        "list_metrics", "run_report_template"),
                defs.keySet().stream().sorted().toList());
    }

    @Test
    @DisplayName("每个工具的描述都说清了「什么时候用」")
    void descriptionsExplainWhenToUse() {
        for (ToolDefinition d : definitions().values()) {
            assertNotNull(d.description(), d.name() + " 缺少描述");
            assertTrue(d.description().length() > 60,
                    d.name() + " 的描述过短，模型无法据此判断何时调用：" + d.description());
            assertTrue(d.description().contains("什么时候用"),
                    d.name() + " 的描述没有说明使用时机");
        }
    }

    @Test
    @DisplayName("参数 schema 正确生成，必填标记生效")
    void parameterSchemaIsCorrect() {
        String schema = definitions().get("run_report_template").inputSchema();
        assertTrue(schema.contains("templateId"), "缺少 templateId 参数");
        assertTrue(schema.contains("paramsJson"), "缺少 paramsJson 参数");
        assertTrue(schema.contains("required"), "缺少必填声明");
        // ToolContext 是框架注入的，绝不能出现在给模型的 schema 里，
        // 否则模型会尝试自己构造它，也意味着权限上下文暴露了
        assertFalse(schema.contains("toolContext"),
                "ToolContext 不应出现在参数 schema 中——它是框架注入的，且承载权限信息");
    }

    @Test
    @DisplayName("参数描述给出了具体格式，而不只是参数名")
    void parameterDescriptionsAreConcrete() {
        String schema = definitions().get("run_report_template").inputSchema();
        assertTrue(schema.contains("sales_monthly_summary"),
                "模板 ID 参数应给出实例，模型才知道长什么样");
        assertTrue(schema.contains("startMonth"),
                "参数 JSON 应给出示例结构");

        String sqlSchema = definitions().get("execute_sql").inputSchema();
        assertTrue(sqlSchema.contains("MySQL"), "应说明 SQL 方言");
    }

    @Test
    @DisplayName("ask_clarification 设为 returnDirect：反问要直接给用户，不再过一轮模型")
    void clarificationReturnsDirect() {
        ToolCallback cb = Arrays.stream(ToolCallbacks.from(tools()))
                .filter(c -> "ask_clarification".equals(c.getToolDefinition().name()))
                .findFirst().orElseThrow();
        assertTrue(cb.getToolMetadata().returnDirect(),
                "反问应当直接结束本轮，让模型再包装一层既慢又容易改写掉选项");
    }

    @Test
    @DisplayName("缺少 ToolContext 时工具明确报错，而不是静默降级")
    void failsLoudWithoutToolContext() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> tools().listMetrics(null));
        assertTrue(e.getMessage().contains("ToolContext"));
    }

    @Test
    @DisplayName("未知表名返回可修复的提示，而不是抛异常中断整轮")
    void unknownTableReturnsFixableHint() {
        SemanticTestSupport.RunContextFixture fixture = SemanticTestSupport.newContext();
        String out = tools().getTableSchema("fact_sales,dim_shop", fixture.toolContext());

        assertTrue(out.contains("不存在"), "要明确说不存在");
        assertTrue(out.contains("fact_order"), "要列出真实可用的表，模型才改得对");
        assertEquals(1, fixture.context().getSteps().size());
        assertEquals("failed", fixture.context().getSteps().get(0).toEvent().get("status"));
    }

    @Test
    @DisplayName("表结构输出包含枚举中文映射与口径提醒——这是 DDL 给不了的部分")
    void schemaOutputCarriesBusinessKnowledge() {
        SemanticTestSupport.RunContextFixture fixture = SemanticTestSupport.newContext();
        String out = tools().getTableSchema("fact_order,dim_region", fixture.toolContext());

        assertTrue(out.contains("paid=已支付"), "缺少订单状态的中文映射");
        assertTrue(out.contains("refunded=已退款"));
        assertTrue(out.contains("可用连接"), "应给出被批准的连接路径");
        assertTrue(out.contains("fact_order.region_id = dim_region.region_id"));
    }

    @Test
    @DisplayName("指标输出带上口径边界与已知分歧")
    void metricsOutputCarriesFilters() {
        SemanticTestSupport.RunContextFixture fixture = SemanticTestSupport.newContext();
        String out = tools().listMetrics(fixture.toolContext());

        assertTrue(out.contains("口径边界"), "指标必须带口径边界");
        assertTrue(out.contains("order_status IN ('paid', 'shipped', 'completed')"));
        assertTrue(out.contains("⚠"), "已知口径分歧要标出来，供模型判断是否需要反问");
    }
}
