package com.wisesoft.agent.sql;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 查询结果。
 * <p>
 * 同一份数据要服务两个完全不同的消费者，所以设计上做了区分：
 * <ul>
 *   <li>{@link #rows} 给前端，完整数据用于渲染表格和图表</li>
 *   <li>{@link #toModelText()} 给模型，是**截断后**的文本预览</li>
 * </ul>
 * 千行结果集原样塞进对话历史会瞬间打爆上下文窗口，而模型解读数据其实
 * 只需要看到前几行加上聚合信息。这个区分是报表 Agent 能跑长对话的前提。
 *
 * @author yuanke
 */
@Data
@AllArgsConstructor
public class QueryResult {

    /** 列名，保持 SQL 中的顺序 */
    private List<String> columns;

    /** 数据行 */
    private List<Map<String, Object>> rows;

    /** 实际执行的 SQL */
    private String sql;

    /** 执行耗时(ms) */
    private long elapsedMs;

    /** 是否因为达到行数上限而被截断 */
    private boolean truncated;

    /** 回灌给模型的预览行数上限 */
    private static final int MODEL_PREVIEW_ROWS = 20;

    /** 单个单元格在预览里的字符上限 */
    private static final int CELL_MAX_CHARS = 60;

    public int rowCount() {
        return rows == null ? 0 : rows.size();
    }

    /**
     * 渲染成给模型看的 Markdown 表格（截断版）。
     * 超出预览行数时明确写出总行数，让模型知道自己看到的不是全部，
     * 避免它基于前 20 行下"总共就这些"的结论。
     */
    public String toModelText() {
        if (rows == null || rows.isEmpty()) {
            return "查询成功，但没有匹配的数据（0 行）。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append("|").append(" --- |".repeat(columns.size())).append("\n");

        int limit = Math.min(rows.size(), MODEL_PREVIEW_ROWS);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = rows.get(i);
            sb.append("| ");
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) {
                    sb.append(" | ");
                }
                sb.append(cell(row.get(columns.get(c))));
            }
            sb.append(" |\n");
        }
        if (rows.size() > limit) {
            sb.append("\n（以上为前 ").append(limit).append(" 行，共 ")
                    .append(rows.size()).append(" 行）");
        } else {
            sb.append("\n（共 ").append(rows.size()).append(" 行）");
        }
        if (truncated) {
            sb.append("\n注意：结果已达到行数上限被截断，如需完整数据请缩小查询范围。");
        }
        return sb.toString();
    }

    private String cell(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v).replace("|", "\\|").replace("\n", " ");
        return s.length() > CELL_MAX_CHARS ? s.substring(0, CELL_MAX_CHARS) + "…" : s;
    }
}
