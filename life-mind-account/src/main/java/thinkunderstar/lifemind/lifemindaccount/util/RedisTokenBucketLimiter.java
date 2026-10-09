package thinkunderstar.lifemind.lifemindaccount.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 基于 Redis + Lua 的令牌桶限流器。
 *
 * <p>令牌桶算法：允许一定程度的突发流量，长期平均速率受 rate 控制。
 * 适合"限制平均 QPS，但允许短时突发"的场景。
 *
 * <p>Lua 脚本保证"读令牌 → 补令牌 → 判断 → 扣令牌 → 写回"整个流程原子执行，
 * 避免并发场景下的竞态问题。
 */
@Slf4j
@Component
public class RedisTokenBucketLimiter {

    private final StringRedisTemplate redisTemplate;

    public RedisTokenBucketLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 令牌桶 Lua 脚本。
     * KEYS[1] : 桶的 key
     * ARGV[1] : capacity   桶容量（允许的最大突发数）
     * ARGV[2] : rate       每秒补充的令牌数（支持小数）
     * ARGV[3] : now        当前时间戳（秒）
     * ARGV[4] : requested  本次请求消耗的令牌数
     * 返回 : 1 允许，0 拒绝
     */
    private static final String TOKEN_BUCKET_SCRIPT =
            "local key = KEYS[1] " +
                    "local capacity = tonumber(ARGV[1]) " +
                    "local rate = tonumber(ARGV[2]) " +
                    "local now = tonumber(ARGV[3]) " +
                    "local requested = tonumber(ARGV[4]) " +

                    "local tokens = tonumber(redis.call('hget', key, 'tokens') or capacity) " +
                    "local lastRefresh = tonumber(redis.call('hget', key, 'refresh_time') or now) " +

                    "local delta = math.max(0, (now - lastRefresh) * rate) " +
                    "local newTokens = math.min(capacity, tokens + delta) " +

                    "local allowed = newTokens >= requested " +
                    "if allowed then " +
                    "    newTokens = newTokens - requested " +
                    "end " +

                    "redis.call('hset', key, 'tokens', newTokens) " +
                    "redis.call('hset', key, 'refresh_time', now) " +

                    // TTL：装满桶的时间 + 10 秒缓冲，避免 key 无限累积
                    "local ttl = math.ceil(capacity / rate) + 10 " +
                    "redis.call('expire', key, ttl) " +

                    "return allowed and 1 or 0";

    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(TOKEN_BUCKET_SCRIPT, Long.class);

    /**
     * 核心方法：尝试获取令牌。
     *
     * @param key       限流键，如 "rate:user:123"
     * @param capacity  桶容量（允许的最大突发量）
     * @param rate      每秒补充的令牌数（支持小数，如 0.5 表示每 2 秒 1 个）
     * @param requested 本次消耗的令牌数，通常为 1
     * @return true 允许通过，false 被限流
     */
    public boolean tryAcquire(String key, long capacity, double rate, int requested) {
        if (rate <= 0) {
            throw new IllegalArgumentException("rate must be positive");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (requested <= 0) {
            throw new IllegalArgumentException("requested must be positive");
        }

        long now = System.currentTimeMillis() / 1000;

        Long result = redisTemplate.execute(
                SCRIPT,
                Collections.singletonList(key),
                String.valueOf(capacity),
                String.valueOf(rate),
                String.valueOf(now),
                String.valueOf(requested)
        );

        return result != null && result == 1L;
    }

    /**
     * 单令牌版本（最常用）。
     */
    public boolean tryAcquire(String key, long capacity, double rate) {
        return tryAcquire(key, capacity, rate, 1);
    }

    // ==================== 业务化方法 ====================

    /**
     * 按用户限流。用于登录、发帖、评论等需要按用户限制的场景。
     */
    public boolean tryAcquireByUser(Object userId, long capacity, double rate) {
        return tryAcquire("rate:user:" + userId, capacity, rate, 1);
    }

    /**
     * 按 IP 限流。用于匿名接口、公开 API。
     */
    public boolean tryAcquireByIp(String ip, long capacity, double rate) {
        return tryAcquire("rate:ip:" + ip, capacity, rate, 1);
    }

    /**
     * 按 API 全局限流。用于保护某个高消耗接口。
     */
    public boolean tryAcquireGlobal(String api, long capacity, double rate) {
        return tryAcquire("rate:api:" + api, capacity, rate, 1);
    }

    /**
     * 按业务标识限流（邮箱、手机号等）。
     */
    public boolean tryAcquireByTarget(String type, String target, long capacity, double rate) {
        return tryAcquire("rate:" + type + ":" + target, capacity, rate, 1);
    }

    // ==================== 运维辅助 ====================

    /**
     * 查询当前剩余令牌数，用于监控和调试。返回 -1 表示 key 不存在。
     */
    public double getRemainingTokens(String key) {
        Object tokens = redisTemplate.opsForHash().get(key, "tokens");
        if (tokens == null) {
            return -1;
        }
        return Double.parseDouble(tokens.toString());
    }

    /**
     * 重置某个 key 的限流状态。用于测试或管理员手动解封。
     */
    public void reset(String key) {
        redisTemplate.delete(key);
    }
}