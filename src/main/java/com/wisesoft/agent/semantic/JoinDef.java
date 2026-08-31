package com.wisesoft.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 批准的 join 路径（对应 semantic-model/joins.yml）
 * <p>
 * SqlGuard 会拿生成 SQL 里的每一个 join 条件来这里比对，
 * 没登记的直接拒绝。这挡住的是 NL2SQL 最贵的一类错误：
 * 连接路径错了，SQL 能跑、结果也有，但数字是错的——
 * 语法校验和执行校验都发现不了，只有白名单能。
 *
 * @author yuanke
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class JoinDef {

    /** 路径标识，命名规范 {左表}__{右表} */
    private String id;

    private String left;

    private String right;

    /** INNER / LEFT */
    private String type = "INNER";

    /** 连接条件，形如 "a.col = b.col" */
    private String on;

    /** 基数关系，如 n:1。1:n 的方向上做聚合会放大金额，是常见错误源。 */
    private String cardinality;

    private String description;

    /**
     * 连接条件的规范化形式：去空白、统一小写、等号两侧按字典序排列。
     * 这样 "a.x = b.y" 和 "b.y=a.x" 会得到同一个 key，比对时不受书写顺序影响。
     */
    public String normalizedOn() {
        return normalize(on);
    }

    public static String normalize(String onClause) {
        if (onClause == null) {
            return "";
        }
        String s = onClause.replaceAll("\\s+", "").toLowerCase();
        int eq = s.indexOf('=');
        if (eq <= 0 || eq == s.length() - 1) {
            return s;
        }
        String l = s.substring(0, eq);
        String r = s.substring(eq + 1);
        return l.compareTo(r) <= 0 ? l + "=" + r : r + "=" + l;
    }
}
