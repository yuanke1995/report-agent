package com.wisesoft.agent.controller;

import com.wisesoft.agent.dto.ResultJson;
import com.wisesoft.agent.semantic.MetricDef;
import com.wisesoft.agent.semantic.ReportTemplate;
import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.semantic.TableDef;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义层查看接口。
 * <p>
 * 语义层要能被业务方评审才有意义——口径对不对，写 SQL 的人说了不算，
 * 得让业务负责人能看懂、能挑错。这几个接口就是给评审用的。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/semantic")
@RequiredArgsConstructor
@Tag(name = "语义层", description = "查看已加载的表、指标口径、join 路径与报表模板")
public class SemanticController {

    private final SemanticModel model;

    @Operation(summary = "语义层概览")
    @GetMapping("/overview")
    public ResultJson<Map<String, Object>> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tables", model.getTables().values().stream()
                .map(t -> Map.of(
                        "table", t.getTable(),
                        "displayName", nullSafe(t.getDisplayName()),
                        "grain", nullSafe(t.getGrain()),
                        "columnCount", t.getColumns().size(),
                        "synonyms", t.getSynonyms()))
                .toList());
        m.put("metrics", model.getMetrics().values().stream()
                .map(x -> Map.of(
                        "name", x.getName(),
                        "displayName", nullSafe(x.getDisplayName()),
                        "unit", nullSafe(x.getUnit()),
                        "hasCaveats", !x.getCaveats().isEmpty()))
                .toList());
        m.put("joins", model.getJoins().keySet());
        m.put("templates", model.getTemplates().values().stream()
                .map(t -> Map.of("id", t.getId(), "name", nullSafe(t.getName())))
                .toList());
        m.put("forbiddenJoinCount", model.getForbiddenJoins().size());
        return ResultJson.ok(m);
    }

    @Operation(summary = "表定义详情")
    @GetMapping("/table/{name}")
    public ResultJson<TableDef> table(
            @Parameter(description = "物理表名") @PathVariable("name") String name) {
        TableDef t = model.table(name);
        return t == null ? ResultJson.error(404, "表不存在于语义层: " + name) : ResultJson.ok(t);
    }

    @Operation(summary = "全部指标口径", description = "含 requiredFilters（口径边界）与 caveats（已知分歧）")
    @GetMapping("/metrics")
    public ResultJson<List<MetricDef>> metrics() {
        return ResultJson.ok(List.copyOf(model.getMetrics().values()));
    }

    @Operation(summary = "报表模板详情")
    @GetMapping("/template/{id}")
    public ResultJson<ReportTemplate> template(
            @Parameter(description = "模板 ID") @PathVariable("id") String id) {
        ReportTemplate t = model.template(id);
        return t == null ? ResultJson.error(404, "模板不存在: " + id) : ResultJson.ok(t);
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
