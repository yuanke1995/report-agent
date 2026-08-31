package com.wisesoft.agent.service;

import com.wisesoft.agent.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 接口限流（Redis 固定窗口计数：INCR + 首次 EXPIRE）
 * <p>
 * 搬自 dtbd-ai-service，逻辑未改。要点：
 * - 匿名请求落 IP 维度，避免匿名共享池互相挤兑
 * - Redis 不可用时放行（限流是保护措施，不该比业务先挂）
 * - 孤儿键自愈：INCR 成功但 EXPIRE 失败的键在下次访问时补设过期
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final String PREFIX = "report-agent:ratelimit:";
    private static final int WINDOW_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;
    private final ConfigService configService;

    /**
     * @param bucket   限流桶名（chat / query，对应配置 ratelimit.{bucket}PerMinute）
     * @param identity 限流维度标识（user:xxx / ip:x.x.x.x）
     */
    public void checkRateLimit(String bucket, String identity) {
        if (!configService.getBoolean("ratelimit.enabled")) {
            return;
        }
        int limit = configService.getInt("ratelimit." + bucket + "PerMinute", defaultLimit(bucket));
        if (limit <= 0) {
            return;
        }
        String key = PREFIX + bucket + ":" + identity;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, Duration.ofSeconds(WINDOW_SECONDS));
            } else {
                Long ttl = redisTemplate.getExpire(key);
                if (ttl != null && ttl < 0) {
                    redisTemplate.expire(key, Duration.ofSeconds(WINDOW_SECONDS));
                }
            }
        } catch (Exception e) {
            log.warn("[RateLimit] Redis 不可用，放行 (bucket={}): {}", bucket, e.getMessage());
            return;
        }
        if (count == null) {
            return;
        }
        if (count > limit) {
            long ttl;
            try {
                Long t = redisTemplate.getExpire(key);
                ttl = (t != null && t > 0) ? t : WINDOW_SECONDS;
            } catch (Exception e) {
                ttl = WINDOW_SECONDS;
            }
            log.info("[RateLimit] 触发限流 bucket={} identity={} count={}/{}", bucket, identity, count, limit);
            throw new BizException(429, "请求过于频繁，请 " + ttl + " 秒后重试");
        }
    }

    private int defaultLimit(String bucket) {
        return switch (bucket) {
            case "chat" -> 10;
            case "query" -> 30;
            default -> 30;
        };
    }
}
