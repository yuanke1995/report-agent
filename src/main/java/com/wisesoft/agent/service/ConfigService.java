package com.wisesoft.agent.service;

import com.wisesoft.agent.common.BizException;
import com.wisesoft.agent.mapper.AgentConfigMapper;
import com.wisesoft.agent.model.AgentConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行期配置中心（DB 存储 + 内存缓存 + Redis 广播）
 * <p>
 * 报表智能体里最需要热调的是提示词——SQL 生成的 system prompt 要反复迭代，
 * 每改一次都重启服务是不可接受的。所以提示词和各类阈值都放这里，保存即生效。
 * <p>
 * 多实例场景靠 Redis pub/sub 同步：任一实例保存后广播，其他实例重载缓存。
 * Redis 不可用时退化为单实例模式（只影响其他实例的同步时效，不影响本实例正确性）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    public static final String CONFIG_CHANNEL = "report-agent:config:changed";

    /**
     * 可编辑白名单 + 默认值。不在这份表里的 key 一律拒绝写入，
     * 防止接口被当成任意 KV 存储用。
     */
    private static final Map<String, String[]> DEFAULTS = new LinkedHashMap<>();

    static {
        // value 格式：{默认值, 说明}
        def("chat.model", "", "对话模型名（留空用 application.yml 的配置）");
        def("chat.temperature", "0.1", "生成温度。SQL 生成要的是确定性，别调高。");
        def("chat.sseTimeoutMs", "300000", "SSE 超时(毫秒)");

        def("agent.maxSteps", "8", "ReAct 最大轮次，超过即优雅终止");
        def("agent.maxSqlRepairs", "2", "SQL 出错后最大自修正次数");
        def("agent.systemPrompt", "", "Agent 主提示词（留空用内置默认）");
        def("agent.showTrace", "true", "是否向前端推送 Agent 执行步骤");

        def("sql.maxRows", "1000", "单次查询最大返回行数");
        def("sql.timeoutSeconds", "20", "单次查询超时(秒)");
        def("sql.explainDryRun", "true", "执行前先跑 EXPLAIN 干跑校验");
        def("sql.forceLimit", "true", "无 LIMIT 的查询强制注入 LIMIT");

        def("nl2sql.enabled", "true", "模板未命中时是否启用 NL2SQL 兜底");
        def("nl2sql.candidateTables", "6", "Schema Linking 召回的候选表数量");
        def("nl2sql.fewShotCount", "3", "注入的 golden SQL 示例条数");

        def("ratelimit.enabled", "true", "接口限流总开关");
        def("ratelimit.chatPerMinute", "10", "问答限频：次/分钟/用户(0=不限)");
        def("ratelimit.queryPerMinute", "30", "查询限频：次/分钟/用户(0=不限)");

        def("clarify.enabled", "true", "歧义时是否反问用户而不是猜");
    }

    private static void def(String key, String value, String desc) {
        DEFAULTS.put(key, new String[]{value, desc});
    }

    private final AgentConfigMapper configMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;

    private volatile Map<String, String> cache = new HashMap<>();

    @PostConstruct
    public void init() {
        ensureDefaults();
        reload();
        subscribe();
        log.info("配置中心加载完成，共 {} 项", cache.size());
    }

    /** 存量升级：新增的默认项自动补入 DB，已有值不覆盖 */
    private void ensureDefaults() {
        List<AgentConfig> existing = configMapper.selectList(null);
        Map<String, AgentConfig> byKey = new HashMap<>();
        existing.forEach(c -> byKey.put(c.getCfgKey(), c));
        DEFAULTS.forEach((key, meta) -> {
            AgentConfig cur = byKey.get(key);
            if (cur == null) {
                AgentConfig c = new AgentConfig();
                c.setCfgKey(key);
                c.setCfgValue(meta[0]);
                c.setCfgDesc(meta[1]);
                c.setUpdatedAt(LocalDateTime.now());
                configMapper.insert(c);
            } else if (!meta[1].equals(cur.getCfgDesc())) {
                // 说明文案变了就同步，值不动
                cur.setCfgDesc(meta[1]);
                configMapper.updateById(cur);
            }
        });
    }

    public void reload() {
        Map<String, String> fresh = new HashMap<>();
        configMapper.selectList(null).forEach(c -> fresh.put(c.getCfgKey(), c.getCfgValue()));
        this.cache = fresh;
    }

    private void subscribe() {
        try {
            listenerContainer.addMessageListener(new MessageListener() {
                @Override
                public void onMessage(Message message, byte[] pattern) {
                    log.info("[Config] 收到配置变更广播，重载缓存");
                    reload();
                }
            }, new ChannelTopic(CONFIG_CHANNEL));
        } catch (Exception e) {
            log.warn("[Config] Redis 订阅失败，退化为单实例模式：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------

    public String get(String key) {
        String v = cache.get(key);
        if (v != null && !v.isBlank()) {
            return v;
        }
        String[] meta = DEFAULTS.get(key);
        return meta == null ? null : meta[0];
    }

    public String get(String key, String def) {
        String v = get(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    public int getInt(String key, int def) {
        try {
            String v = get(key);
            return (v == null || v.isBlank()) ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("[Config] {} 不是合法整数，回退默认值 {}", key, def);
            return def;
        }
    }

    public long getLong(String key, long def) {
        try {
            String v = get(key);
            return (v == null || v.isBlank()) ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            log.warn("[Config] {} 不是合法长整数，回退默认值 {}", key, def);
            return def;
        }
    }

    public double getDouble(String key, double def) {
        try {
            String v = get(key);
            return (v == null || v.isBlank()) ? def : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            log.warn("[Config] {} 不是合法小数，回退默认值 {}", key, def);
            return def;
        }
    }

    public boolean getBoolean(String key) {
        return "true".equalsIgnoreCase(get(key));
    }

    // ------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------

    /** 批量更新配置，保存后广播通知其他实例 */
    public Map<String, String> update(Map<String, String> changes) {
        Map<String, String> applied = new LinkedHashMap<>();
        changes.forEach((key, value) -> {
            if (!DEFAULTS.containsKey(key)) {
                throw new BizException("不允许修改的配置项: " + key);
            }
            AgentConfig c = new AgentConfig();
            c.setCfgKey(key);
            c.setCfgValue(value);
            c.setUpdatedAt(LocalDateTime.now());
            if (configMapper.updateById(c) == 0) {
                c.setCfgDesc(DEFAULTS.get(key)[1]);
                configMapper.insert(c);
            }
            applied.put(key, value);
        });
        reload();
        broadcast();
        log.info("[Config] 配置已更新: {}", applied.keySet());
        return applied;
    }

    private void broadcast() {
        try {
            redisTemplate.convertAndSend(CONFIG_CHANNEL, String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("[Config] 广播失败，其他实例需等待重启才能生效：{}", e.getMessage());
        }
    }

    /** 当前全量配置快照（含说明），设置页展示用 */
    public List<Map<String, String>> snapshot() {
        return DEFAULTS.entrySet().stream()
                .map(e -> Map.of(
                        "key", e.getKey(),
                        "value", get(e.getKey()) == null ? "" : get(e.getKey()),
                        "desc", e.getValue()[1],
                        "default", e.getValue()[0]))
                .toList();
    }

    /** Redis pub/sub 的 channel 名，供健康检查等场景引用 */
    public static byte[] channelBytes() {
        return CONFIG_CHANNEL.getBytes(StandardCharsets.UTF_8);
    }
}
