package thinkunderstar.lifemind.lifemindaccount.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 图形验证码校验器。
 *
 * <p>验证码由 {@code CaptchaServiceImpl} 用 {@link StringRedisTemplate} 写入，
 * key 就是前端生成的临时 key（UUID）。这里必须用同一种模板读取，
 * 否则序列化方式不一致会读不到值（前者是字符串序列化，后者是 JDK 序列化）。
 *
 * <p>校验采用「一次通过即失效」：比对成功后立刻删除 key，
 * 避免同一个验证码被反复提交用来试探密码。
 */
@Slf4j
@Component
public class CaptchaVerifier {

    /**
     * 同一个验证码 key 允许的校验次数。
     * 一次通过就失效，这里只是给「输错一两个字符」留重试余地，
     * 同时限制拿同一个 key 无限试探。
     */
    private static final long MAX_ATTEMPTS = 5L;

    /**
     * 校验次数的统计窗口，与验证码本身的 30 分钟保持一致。
     *
     * <p>这里刻意不用 {@link RedisTokenBucketLimiter}：它的 key TTL 是
     * {@code ceil(capacity/rate) + 10} 秒，容量 5、速率 5/30 时只有 11 秒，
     * 空闲 11 秒后计数就被重置，达不到「30 分钟内最多 5 次」的硬限制。
     * 用固定窗口计数器语义才准确。
     */
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(30);

    private final StringRedisTemplate stringRedisTemplate;

    public CaptchaVerifier(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 校验图形验证码，通过后该验证码立即失效。
     *
     * @param tempKey     前端生成的临时 key
     * @param captchaCode 用户填写的验证码
     * @return true 校验通过
     */
    public boolean verify(String tempKey, String captchaCode) {
        //1. 次数限制：防止拿同一个 key 无限试探验证码
        if (isAttemptLimited(tempKey)) {
            log.warn("验证码校验次数超过限制, tempKey={}", tempKey);
            return false;
        }

        //2. 读取存储值（写入时已转小写；equalsIgnoreCase 对存量的大写数据也兼容）
        String stored = stringRedisTemplate.opsForValue().get(tempKey);
        if (stored == null) {
            //不存在或已过期：可能没申请验证码，也可能已经用过一次
            return false;
        }

        //3. 不区分大小写比对
        if (!stored.equalsIgnoreCase(captchaCode.trim())) {
            return false;
        }

        //4. 校验通过：删除验证码与计数，保证一次性
        stringRedisTemplate.delete(tempKey);
        stringRedisTemplate.delete(attemptKey(tempKey));
        return true;
    }

    /**
     * 判断该 key 的校验次数是否已超限。
     *
     * <p>INCR 是原子操作，天然抗并发；只有首次（返回 1）才设置过期时间，
     * 保证计数窗口不会因为后续请求而被不断续期。
     *
     * @param tempKey 临时 key
     * @return true 表示已超过允许次数
     */
    private boolean isAttemptLimited(String tempKey) {
        Long attempts = stringRedisTemplate.opsForValue().increment(attemptKey(tempKey));
        if (attempts != null && attempts == 1L) {
            stringRedisTemplate.expire(attemptKey(tempKey), ATTEMPT_WINDOW);
        }
        return attempts != null && attempts > MAX_ATTEMPTS;
    }

    /**
     * 校验次数的计数 key。用独立前缀，避免与验证码本身的 key 以及限流 key 撞车。
     */
    private String attemptKey(String tempKey) {
        return "captcha:attempt:" + tempKey;
    }
}
