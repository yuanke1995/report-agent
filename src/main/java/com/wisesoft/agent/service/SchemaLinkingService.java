package com.wisesoft.agent.service;

import com.wisesoft.agent.semantic.ColumnDef;
import com.wisesoft.agent.semantic.GoldenExample;
import com.wisesoft.agent.semantic.JoinDef;
import com.wisesoft.agent.semantic.MetricDef;
import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.semantic.TableDef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Schema Linking：从用户问题里找出"应该查哪些表、用哪些指标口径、参考哪些 golden 示例"。
 * <p>
 * 这是 NL2SQL 准确率的第一大变量（表一多，全量 schema 根本塞不进上下文，
 * 模型只能在被选中的表里发挥）。当前 6 张表规模用<b>规则召回</b>就够：
 * <ol>
 *   <li><b>同义词精确命中</b>（置信度最高）——"销售额"命中 gmv 指标 → 订单表加分</li>
 *   <li><b>二元组关键词打分</b>——问题切成 2-gram，去匹配表/列/指标描述文本</li>
 *   <li><b>golden 示例关联</b>——命中示例的问题 → 示例用到的表加分</li>
 * </ol>
 * 向量召回的接口留给表变多之后的升级（Redis VectorStore + embedding 已配好，
 * 只是当前 6 张表不需要为它引入额外的运行时依赖）。
 * <p>
 * 打分规则刻意保持简单可解释——出问题能一眼看出为什么选了这张表，
 * 比一个黑盒相似度模型好调得多。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaLinkingService {

    private final SemanticModel model;
    private final ConfigService configService;

    /** 同义词命中得分（最高置信度） */
    private static final double SYNONYM_TABLE_SCORE = 100.0;
    private static final double SYNONYM_METRIC_SCORE = 50.0;
    private static final double METRIC_JOIN_SCORE = 30.0;
    /** 指标描述关键词命中后给指标主表加的分数 */
    private static final double METRIC_KEYWORD_SCORE = 3.0;
    /** 列描述关键词命中（比表描述更精确，权重更高） */
    private static final double COLUMN_KEYWORD_SCORE = 2.0;
    private static final double TABLE_KEYWORD_SCORE = 1.0;
    private static final double GOLDEN_TABLE_SCORE = 8.0;

    /** 召回结果 */
    public record LinkingResult(List<String> tables,
                                List<String> metricNames,
                                List<GoldenExample> goldenExamples) {

        public boolean isEmpty() {
            return tables.isEmpty();
        }
    }

    public LinkingResult link(String question) {
        if (question == null || question.isBlank()) {
            return new LinkingResult(List.of(), List.of(), List.of());
        }

        Map<String, Double> tableScores = new LinkedHashMap<>();
        Set<String> metricNames = new LinkedHashSet<>();

        // 1. 同义词精确命中
        synonymHit(question, tableScores, metricNames);

        // 2. 二元组关键词打分
        Set<String> grams = bigrams(question);
        if (!grams.isEmpty()) {
            keywordHit(grams, tableScores, metricNames);
        }

        // 3. golden 示例召回
        List<GoldenExample> golden = recallGolden(question, tableScores);

        // 按得分排序取候选表
        List<String> tables = tableScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .limit(candidateTableCount())
                .toList();

        return new LinkingResult(tables, List.copyOf(metricNames), golden);
    }

    /** 同义词命中：表/指标的 名称+显示名+同义词 任一出现在问题里即命中 */
    private void synonymHit(String question, Map<String, Double> tableScores, Set<String> metricNames) {
        for (TableDef t : model.getTables().values()) {
            if (containsAny(question, t.getTable(), t.getDisplayName())
                    || containsAny(question, t.getSynonyms())) {
                tableScores.merge(t.getTable(), SYNONYM_TABLE_SCORE, Double::sum);
            }
        }
        for (MetricDef m : model.getMetrics().values()) {
            if (containsAny(question, m.getName(), m.getDisplayName())
                    || containsAny(question, m.getSynonyms())) {
                metricNames.add(m.getName());
                tableScores.merge(m.getBaseTable(), SYNONYM_METRIC_SCORE, Double::sum);
                // 指标声明了 requiredJoins：这些 join 涉及的表也要进候选
                for (String jid : m.getRequiredJoins()) {
                    JoinDef j = model.joinById(jid);
                    if (j != null) {
                        tableScores.merge(j.getLeft(), METRIC_JOIN_SCORE, Double::sum);
                        tableScores.merge(j.getRight(), METRIC_JOIN_SCORE, Double::sum);
                    }
                }
            }
        }
    }

    /** 二元组关键词打分：列描述 > 表描述 > 指标描述 */
    private void keywordHit(Set<String> grams, Map<String, Double> tableScores, Set<String> metricNames) {
        for (TableDef t : model.getTables().values()) {
            String tableDoc = doc(t.getDisplayName(), t.getDescription(), t.getGrain(), t.getNotes());
            for (String g : grams) {
                if (tableDoc.contains(g)) {
                    tableScores.merge(t.getTable(), TABLE_KEYWORD_SCORE, Double::sum);
                }
            }
            for (ColumnDef c : t.getColumns()) {
                String colDoc = doc(c.getDisplayName(), c.getDescription(), c.getUnit(), c.getNotes());
                if (c.getSynonyms() != null) {
                    colDoc += String.join("", c.getSynonyms());
                }
                if (c.getEnums() != null) {
                    colDoc += String.join("", c.getEnums().values());
                }
                for (String g : grams) {
                    if (colDoc.contains(g)) {
                        tableScores.merge(t.getTable(), COLUMN_KEYWORD_SCORE, Double::sum);
                    }
                }
            }
        }
        for (MetricDef m : model.getMetrics().values()) {
            String metricDoc = doc(m.getDisplayName(), m.getDescription());
            if (m.getSynonyms() != null) {
                metricDoc += String.join("", m.getSynonyms());
            }
            for (String g : grams) {
                if (metricDoc.contains(g)) {
                    metricNames.add(m.getName());
                    tableScores.merge(m.getBaseTable(), METRIC_KEYWORD_SCORE, Double::sum);
                    break;
                }
            }
        }
    }

    /** golden 召回：问题里出现示例的任一关键词即召回，命中越多越靠前 */
    private List<GoldenExample> recallGolden(String question, Map<String, Double> tableScores) {
        List<GoldenExample> hit = new ArrayList<>();
        for (GoldenExample ex : model.getGoldenExamples().values()) {
            int count = 0;
            for (String kw : ex.getKeywords()) {
                if (kw != null && !kw.isBlank() && question.contains(kw)) {
                    count++;
                }
            }
            if (count > 0) {
                hit.add(ex);
                for (String t : ex.getTables()) {
                    tableScores.merge(t, GOLDEN_TABLE_SCORE * count, Double::sum);
                }
            }
        }
        hit.sort(Comparator.comparingInt(
                (GoldenExample e) -> (int) e.getKeywords().stream()
                        .filter(k -> question.contains(k)).count()).reversed());
        int limit = configService.getInt("nl2sql.fewShotCount", 3);
        return hit.size() > limit ? hit.subList(0, limit) : hit;
    }

    // ------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------

    /** 候选表数量上限，配置 nl2sql.candidateTables */
    private int candidateTableCount() {
        return configService.getInt("nl2sql.candidateTables", 6);
    }

    /** 问题里是否出现 terms 中的任一（去空白、小写比较） */
    private boolean containsAny(String question, String... terms) {
        for (String t : terms) {
            if (contains(question, t)) {
                return true;
            }
        }
        return false;
    }

    /** 问题里是否出现 list 中的任一 */
    private boolean containsAny(String question, List<String> terms) {
        for (String t : terms) {
            if (contains(question, t)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(String question, String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        return question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                .contains(term.replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
    }

    /** 文档文本：拼接后去空白（二元组匹配时空白是噪音） */
    private String doc(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null) {
                sb.append(p);
            }
        }
        return sb.toString().replaceAll("\\s+", "");
    }

    /** 把问题切成 CJK/字母数字的二元组，如 "上个月销售额" → 上月/个月/月销/销售/售额 */
    private Set<String> bigrams(String question) {
        // 只保留中文、字母、数字
        StringBuilder clean = new StringBuilder();
        for (char c : question.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                clean.append(c);
            }
        }
        Set<String> grams = new LinkedHashSet<>();
        String s = clean.toString();
        for (int i = 0; i + 1 < s.length(); i++) {
            grams.add(s.substring(i, i + 2));
        }
        return grams;
    }
}
