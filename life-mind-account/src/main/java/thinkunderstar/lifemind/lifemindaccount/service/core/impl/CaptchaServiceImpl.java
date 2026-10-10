package thinkunderstar.lifemind.lifemindaccount.service.core.impl;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindaccount.common.ResultCode;
import thinkunderstar.lifemind.lifemindaccount.exception.BusinessException;
import thinkunderstar.lifemind.lifemindaccount.service.core.CaptchaService;
import thinkunderstar.lifemind.lifemindaccount.util.IpUtils;
import thinkunderstar.lifemind.lifemindaccount.util.RedisTokenBucketLimiter;
import thinkunderstar.lifemind.lifemindaccount.util.ValidateUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CaptchaServiceImpl implements CaptchaService {

    /**
     * 单 IP 限流：桶容量 10（允许的突发次数），每秒补充 1 个令牌。
     * 与登录一样按 IP 限制刷新频率，防止有人刷验证码图片消耗资源。
     */
    private static final long CAPTCHA_IP_CAPACITY = 10L;
    private static final double CAPTCHA_IP_RATE = 1.0;

    /**
     * 验证码在 Redis 中的有效期。
     * key 直接用前端生成的临时 key，登录校验时要用同一个 key 取出来比对，
     * 所以这里不能额外加前缀。
     */
    private static final long CAPTCHA_TTL_MINUTES = 30L;

    private final RedisTokenBucketLimiter redisTokenBucketLimiter;
    private final StringRedisTemplate stringRedisTemplate;

    public CaptchaServiceImpl(RedisTokenBucketLimiter redisTokenBucketLimiter,
                              StringRedisTemplate stringRedisTemplate) {
        this.redisTokenBucketLimiter = redisTokenBucketLimiter;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void getCaptcha(HttpServletRequest request, HttpServletResponse response, String tempKey) {

        // ========== 1. 临时 key 格式校验 ==========
        if (!ValidateUtils.uuidValidate(tempKey)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "临时人机验证码的临时Key异常");
        }

        // ========== 2. 按 IP 限流 ==========
        if (!redisTokenBucketLimiter.tryAcquireByIp(
                IpUtils.getClientIp(request), CAPTCHA_IP_CAPACITY, CAPTCHA_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "刷新过于频繁，请稍后再试");
        }

        // ========== 3. 生成图形验证码 ==========
        //定义图形验证码的长和宽
        LineCaptcha lineCaptcha = CaptchaUtil.createLineCaptcha(200, 100);

        // ========== 4. 先把验证码写进 Redis，再输出图片 ==========
        //顺序很重要：先写 Redis，万一响应客户端时失败，验证码依然有效，
        //前端重新拉取即可；反过来则会出现「图片拿到了但服务端没有记录」的死验证码。
        //统一转小写存储，校验时用 equalsIgnoreCase 比对，实现「不区分大小写」。
        stringRedisTemplate.opsForValue().set(
                tempKey,
                lineCaptcha.getCode().toLowerCase(),
                CAPTCHA_TTL_MINUTES,
                TimeUnit.MINUTES
        );

        // ========== 5. 图形验证码写出到响应流 ==========
        try {
            lineCaptcha.write(response.getOutputStream());
        } catch (IOException e) {
            //客户端断开等情况：这里返回的是图片流，已经无法再写业务响应体，
            //只记日志并抛业务异常，交给 GlobalExceptionHandler 处理
            log.error("输出图形验证码失败, tempKey={}", tempKey, e);
            throw new BusinessException(ResultCode.ERROR, "获取验证码失败");
        }
    }
}
