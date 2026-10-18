package thinkunderstar.lifemind.lifemindaccount.service.core.impl;

import cn.hutool.core.date.DateTime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.common.ResultCode;
import thinkunderstar.lifemind.lifemindaccount.dto.request.BindEmailRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.BindPhoneRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangeNicknameRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByEmailRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByOriginalRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByPhoneRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangeUsernameRequest;
import thinkunderstar.lifemind.lifemindaccount.entity.Permission;
import thinkunderstar.lifemind.lifemindaccount.entity.User;
import thinkunderstar.lifemind.lifemindaccount.entity.UserIdentity;
import thinkunderstar.lifemind.lifemindaccount.event.NotificationEvent;
import thinkunderstar.lifemind.lifemindaccount.exception.BusinessException;
import thinkunderstar.lifemind.lifemindaccount.mapper.IdentityPermissionMapper;
import thinkunderstar.lifemind.lifemindaccount.security.LifeMindUserDetails;
import thinkunderstar.lifemind.lifemindaccount.service.core.UserInfoService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserIdentityService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserService;
import thinkunderstar.lifemind.lifemindaccount.util.CaptchaVerifier;
import thinkunderstar.lifemind.lifemindaccount.util.DesensitizeUtils;
import thinkunderstar.lifemind.lifemindaccount.util.IpUtils;
import thinkunderstar.lifemind.lifemindaccount.util.RedisTokenBucketLimiter;
import thinkunderstar.lifemind.lifemindaccount.util.ValidateUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserInfoServiceImpl implements UserInfoService {

    /**
     * 用户状态：1-正常（0 为禁用）
     */
    private static final int USER_STATUS_ENABLED = 1;

    /**
     * 用户级缓存在 Redis 中的 key 前缀，完整 key 为 {@code life-mind:{userId}:xxx}。
     * 登录时写入、网关读取、本服务回查后回填，三处必须保持一致。
     */
    private static final String USER_KEY_PREFIX = "life-mind:";

    /**
     * 权限缓存的 key 后缀，完整 key 为 {@code life-mind:{userId}:scopes}。
     */
    private static final String USER_SCOPES_KEY_SUFFIX = ":scopes";

    /**
     * 权限缓存有效期，与登录时写入的保持一致。
     */
    private static final Duration USER_SCOPES_TTL = Duration.ofDays(7);

    /**
     * 令牌版本号缓存的 key 后缀，完整 key 为 {@code life-mind:{userId}:token-version}。
     * 登录时写入、改密时刷新、网关校验时读取、本服务回查后回填，四处必须保持一致。
     */
    private static final String USER_TOKEN_VERSION_KEY_SUFFIX = ":token-version";

    /**
     * 令牌版本号缓存有效期，与权限缓存保持一致。
     *
     * <p>过期不算问题：网关读不到时会回查本服务的内部接口，只是多一次内部调用，
     * 不会把用户的登录态弄丢（令牌本身的有效期是 30 天）。
     */
    private static final Duration USER_TOKEN_VERSION_TTL = Duration.ofDays(7);

    /**
     * 手机 / 邮箱长度硬上限，用于在进正则之前拦住超长输入，
     * 与 {@code AuthServiceImpl} 中发送验证码时的上限保持一致。
     */
    private static final int MAX_PHONE_LENGTH = 11;
    private static final int MAX_EMAIL_LENGTH = 64;

    /**
     * 昵称长度上限，与 {@code ValidateUtils} 里昵称正则的上限、
     * 以及 {@code AuthServiceImpl} 注册时的上限保持一致。
     */
    private static final int MAX_NICKNAME_LENGTH = 16;

    /**
     * 修改昵称的限流：按 IP、按账号两层，额度与改密一致。
     *
     * <p>额度刻意不另设一套：限流键在 {@code RedisTokenBucketLimiter} 里是按用户 / 按 IP
     * 共用的（{@code rate:user:{userId}}、{@code rate:ip:{ip}}），
     * 同一个用户改昵称和改密码本来就在同一个桶里，用不同额度只会互相干扰。
     *
     * <p>没有「按目标号码」那一层：昵称不是占用型资料（不像手机号 / 邮箱有唯一约束），
     * 不存在拿别人的值去试探的路径。
     */
    private static final long NICKNAME_IP_CAPACITY = 10L;
    private static final double NICKNAME_IP_RATE = 0.2;
    private static final long NICKNAME_USER_CAPACITY = 5L;
    private static final double NICKNAME_USER_RATE = 0.05;

    /**
     * 账户名长度硬上限，用于在进正则之前拦住超长输入，
     * 与 {@code AuthServiceImpl} 注册 / 登录时的上限保持一致（真正的长度规则由正则决定，是 4 ~ 16 位）。
     */
    private static final int MAX_USERNAME_LENGTH = 32;

    /**
     * 账户名的修改冷却：一年只能改一次，改完把时间写进 {@code user.username_change_time}。
     */
    private static final int USERNAME_COOLDOWN_YEARS = 1;

    /**
     * 修改账户名的限流：按 IP、按账号两层，额度与改密、改昵称一致（三者共用同一个限流桶）。
     */
    private static final long USERNAME_IP_CAPACITY = 10L;
    private static final double USERNAME_IP_RATE = 0.2;
    private static final long USERNAME_USER_CAPACITY = 5L;
    private static final double USERNAME_USER_RATE = 0.05;

    /**
     * 绑定手机号 / 邮箱的限流：
     * 单 IP 桶容量 10、每秒补 0.2 个（平均 5 秒 1 次）；
     * 单账号桶容量 5、每 20 秒补 1 个（rate = 0.05），避免一个账号被拿来反复试码；
     * 单手机号 / 单邮箱桶容量 3、每 60 秒补 1 个，挡住登录用户拿别人的号码暴力试验证码。
     */
    private static final long BIND_IP_CAPACITY = 10L;
    private static final double BIND_IP_RATE = 0.2;
    private static final long BIND_USER_CAPACITY = 5L;
    private static final double BIND_USER_RATE = 0.05;
    private static final long BIND_TARGET_CAPACITY = 3L;
    private static final double BIND_TARGET_RATE = 1.0 / 60;

    /**
     * 密码长度硬上限，用于在进正则之前拦住超长输入（BCrypt 本身也只取前 72 字节），
     * 与 {@code AuthServiceImpl} 中注册 / 登录时的上限保持一致。
     */
    private static final int MAX_PASSWORD_LENGTH = 64;

    /**
     * 修改密码的限流：
     * 单 IP 桶容量 10、每秒补 0.2 个（平均 5 秒 1 次）；
     * 单账号桶容量 5、每 20 秒补 1 个（rate = 0.05）。
     *
     * <p>比绑定少一层「按目标号码」：改密不认请求里传的号码，验证码永远发给账号自己已绑的号码，
     * 没有拿别人号码试码的路径，按 IP + 按账号两层足够。
     * 原密码那条路也靠这两层挡爆破——5 个令牌、20 秒才补 1 个，猜测速度被压到很低。
     */
    private static final long PASSWORD_IP_CAPACITY = 10L;
    private static final double PASSWORD_IP_RATE = 0.2;
    private static final long PASSWORD_USER_CAPACITY = 5L;
    private static final double PASSWORD_USER_RATE = 0.05;

    private final UserService userService;
    private final UserIdentityService userIdentityService;
    private final IdentityPermissionMapper identityPermissionMapper;
    private final RedisTemplate<Object, Object> redisTemplate;
    private final RedisTokenBucketLimiter rateLimiter;
    private final CaptchaVerifier captchaVerifier;
    private final PasswordEncoder passwordEncoder;
    private final RocketMQTemplate rocketMQTemplate;

    public UserInfoServiceImpl(UserService userService,
                               UserIdentityService userIdentityService,
                               IdentityPermissionMapper identityPermissionMapper,
                               RedisTemplate<Object, Object> redisTemplate,
                               RedisTokenBucketLimiter rateLimiter,
                               CaptchaVerifier captchaVerifier,
                               PasswordEncoder passwordEncoder, RocketMQTemplate rocketMQTemplate) {
        this.userService = userService;
        this.userIdentityService = userIdentityService;
        this.identityPermissionMapper = identityPermissionMapper;
        this.redisTemplate = redisTemplate;
        this.rateLimiter = rateLimiter;
        this.captchaVerifier = captchaVerifier;
        this.passwordEncoder = passwordEncoder;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public Result<Void> bindPhone(BindPhoneRequest bindPhoneRequest, HttpServletRequest request) {
        if (bindPhoneRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }
        return bindContact(ContactTarget.PHONE, BindMode.BIND,
                bindPhoneRequest.getPhone(), bindPhoneRequest.getCode(), request);
    }

    @Override
    public Result<Void> rebindPhone(BindPhoneRequest bindPhoneRequest, HttpServletRequest request) {
        if (bindPhoneRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }
        return bindContact(ContactTarget.PHONE, BindMode.REBIND,
                bindPhoneRequest.getPhone(), bindPhoneRequest.getCode(), request);
    }

    @Override
    public Result<Void> bindEmail(BindEmailRequest bindEmailRequest, HttpServletRequest request) {
        if (bindEmailRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }
        return bindContact(ContactTarget.EMAIL, BindMode.BIND,
                bindEmailRequest.getEmail(), bindEmailRequest.getCode(), request);
    }

    @Override
    public Result<Void> rebindEmail(BindEmailRequest bindEmailRequest, HttpServletRequest request) {
        if (bindEmailRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }
        return bindContact(ContactTarget.EMAIL, BindMode.REBIND,
                bindEmailRequest.getEmail(), bindEmailRequest.getCode(), request);
    }

    @Override
    public Result<Void> changeNickname(ChangeNicknameRequest changeNicknameRequest, HttpServletRequest request) {

        // ========== 1. 参数非空校验 ==========
        if (changeNicknameRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }

        String nickname = changeNicknameRequest.getNickname();
        if (nickname == null || nickname.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "昵称不能为空");
        }

        // ========== 2. 格式校验 ==========
        // 长度上限先拦一道：超长输入不进正则，与注册时的顺序一致
        if (nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "昵称长度超出限制");
        }
        // 与注册用同一个正则：只放行中文、字母、数字、下划线、连字符，
        // < > ' " & ; \ 等危险字符一律不通过（防脚本注入）
        if (!ValidateUtils.nicknameValidate(nickname)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "昵称格式不正确");
        }

        // ========== 3. 取当前登录用户 + 限流 ==========
        // 只改网关注入的身份对应的用户，前端传什么用户 ID 都不认
        Long userId = currentUserId();
        checkNicknameRateLimit(userId, request);

        // ========== 4. 加载账号并校验状态 ==========
        // 顺带拿到当前昵称，下一步要比对
        User user = loadEnabledUser(userId);

        // ========== 5. 新昵称不能与当前昵称相同 ==========
        // 与换绑手机号 / 邮箱同样的取舍：不做无意义的空写，直接告诉调用方没改
        if (nickname.equals(user.getNickname())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新昵称不能与当前昵称相同");
        }

        // ========== 6. 落库 ==========
        // 昵称没有唯一约束，不会像手机号 / 邮箱那样撞唯一索引，所以不需要处理 DuplicateKeyException；
        // updateTime 由 MyMetaObjectHandler 自动刷新，不需要手工 set
        boolean updated = userService.lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getNickname, nickname)
                .update();

        if (!updated) {
            throw new BusinessException(ResultCode.ERROR, "修改昵称失败，请稍后重试");
        }

        // 不重新签发令牌：昵称不参与 JWT 声明，也不参与权限判定，
        // 其他设备上的登录态不受影响（不像改密要作废旧令牌）
        log.info("修改昵称成功, userId={}", userId);
        return Result.success();
    }

    @Override
    public Result<Void> changeUsername(ChangeUsernameRequest changeUsernameRequest, HttpServletRequest request) {

        // ========== 1. 参数非空校验 ==========
        if (changeUsernameRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }

        String username = changeUsernameRequest.getUsername();
        if (username == null || username.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "账户名不能为空");
        }

        // ========== 2. 格式校验 ==========
        // 长度上限先拦一道：超长输入不进正则，与注册时的顺序一致
        if (username.length() > MAX_USERNAME_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "账户名长度超出限制");
        }
        // 与注册用同一个正则：4 ~ 16 位、字母开头、只允许字母、数字、下划线
        if (!ValidateUtils.usernameValidate(username)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "账户名格式不正确");
        }

        // ========== 3. 取当前登录用户 + 限流 ==========
        // 只改网关注入的身份对应的用户，前端传什么用户 ID 都不认
        Long userId = currentUserId();
        checkUsernameRateLimit(userId, request);

        // ========== 4. 加载账号并校验状态 ==========
        // 顺带拿到当前账户名与上次修改时间，下面两步都要用
        User user = loadEnabledUser(userId);

        // ========== 5. 新账户名不能与当前账户名相同 ==========
        // 忽略大小写比较：库里的唯一索引是大小写不敏感的（utf8mb4_0900_ai_ci），
        // 「alice」改「Alice」在库看来仍是同一个名字，不该白吃掉一年一次的额度
        if (username.equalsIgnoreCase(user.getUsername())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新账户名不能与当前账户名相同");
        }

        // ========== 6. 一年只能改一次 ==========
        LocalDateTime now = LocalDateTime.now();
        checkUsernameCooldown(user, now);

        // ========== 7. 查重：不能与其他账号重名 ==========
        // 排除自己：上面已确认新旧名字不同，排除是为了配合大小写不敏感的比较，避免把自己查出来；
        // deleted 由 @TableLogic 自动追加条件，已注销账号不挡路
        boolean usernameExists = userService.lambdaQuery()
                .eq(User::getUsername, username)
                .ne(User::getId, userId)
                .exists();

        if (usernameExists) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "账户名已被使用");
        }

        // ========== 8. 落库 ==========
        // 冷却时间也写进 WHERE：两个并发请求会同时读到同一个旧时间、同时通过第 6 步，
        // 带上这个条件后只有一个能更新成功（另一个影响行数为 0），
        // 一年一次的额度不会被并发请求绕过。
        boolean updated;
        try {
            updated = userService.lambdaUpdate()
                    .eq(User::getId, userId)
                    .and(w -> w.isNull(User::getUsernameChangeTime)
                            .or()
                            .le(User::getUsernameChangeTime, now.minusYears(USERNAME_COOLDOWN_YEARS)))
                    .set(User::getUsername, username)
                    .set(User::getUsernameChangeTime, now)
                    .update();
        } catch (DuplicateKeyException e) {
            // 并发改成同一个名字时上面的查重会同时通过，靠唯一索引 uk_user_username 兜底
            log.warn("修改账户名重复, userId={}", userId);
            throw new BusinessException(ResultCode.PARAM_ERROR, "账户名已被使用");
        }

        if (!updated) {
            // 走到这里只可能是：第 6 步之后冷却时间被另一个并发请求刷掉了，或者账号刚被注销
            throw new BusinessException(ResultCode.FORBIDDEN, "账户名一年内只能修改一次，请稍后再试");
        }

        // 账户名不参与 JWT 声明，也不参与权限判定：不重新签发令牌、不作废旧令牌，
        // 其他设备上的登录态不受影响。只是下次登录要用新账户名。
        log.info("修改账户名成功, userId={}", userId);
        return Result.success();
    }

    @Override
    public Result<Void> changePasswordByOriginal(ChangePasswordByOriginalRequest changePasswordByOriginalRequest,
                                                 HttpServletRequest request) {
        if (changePasswordByOriginalRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }
        return changePassword(PasswordProof.ORIGINAL,
                changePasswordByOriginalRequest.getOriginalPassword(),
                changePasswordByOriginalRequest.getNewPassword(),
                changePasswordByOriginalRequest.getConfirmPassword(),
                request);
    }

    @Override
    public Result<Void> changePasswordByPhone(ChangePasswordByPhoneRequest changePasswordByPhoneRequest,
                                              HttpServletRequest request) {
        if (changePasswordByPhoneRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }
        return changePassword(PasswordProof.PHONE,
                changePasswordByPhoneRequest.getCode(),
                changePasswordByPhoneRequest.getNewPassword(),
                changePasswordByPhoneRequest.getConfirmPassword(),
                request);
    }

    @Override
    public Result<Void> changePasswordByEmail(ChangePasswordByEmailRequest changePasswordByEmailRequest,
                                              HttpServletRequest request) {
        if (changePasswordByEmailRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }
        return changePassword(PasswordProof.EMAIL,
                changePasswordByEmailRequest.getCode(),
                changePasswordByEmailRequest.getNewPassword(),
                changePasswordByEmailRequest.getConfirmPassword(),
                request);
    }

    /**
     * 修改密码的通用实现：原密码 / 手机验证码 / 邮箱验证码三条路共用这一条路径。
     *
     * <p>三条路的差异只有「怎么证明是本人」这一步（第 5 步），
     * 入参校验、限流、新旧密码比对、落库完全一致，所以用 {@link PasswordProof} 表达，
     * 不拆成三份实现。接口层是三个入口，各自只做参数空判后转发到这里。
     *
     * <p>改密会顺带把用户的令牌版本号 +1 并刷新缓存：JWT 里带着旧版本号，
     * 网关比对不通过，等于把此前签发的所有令牌（含本次请求用的那一个）一次性作废，
     * 改完必须重新登录；这是「改密码踢掉所有旧登录态」的实现方式。
     *
     * @param proof           证明方式（原密码 / 短信验证码 / 邮箱验证码）
     * @param proofValue      证明材料：原密码明文，或 6 位验证码
     * @param newPassword     新密码明文
     * @param confirmPassword 确认新密码，仅用于比对
     * @param request         当前请求，用于解析客户端真实 IP 做限流
     */
    private Result<Void> changePassword(PasswordProof proof, String proofValue, String newPassword,
                                       String confirmPassword, HttpServletRequest request) {

        String proofLabel = proof.getLabel();

        // ========== 1. 参数非空校验 ==========
        if (proofValue == null || proofValue.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, proofLabel + "不能为空");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码不能为空");
        }
        if (confirmPassword == null || confirmPassword.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "确认新密码不能为空");
        }

        // ========== 2. 新密码格式校验 ==========
        // 放在验码之前是刻意的：格式错误属于纯入参问题，不该白烧掉一条一次性验证码。
        // 用与注册、登录同一个正则，改完的密码才能通过登录时的同一套校验。
        if (newPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码长度超出限制");
        }
        if (!ValidateUtils.passwordValidate(newPassword)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码格式不正确，需 8-20 位且同时包含字母和数字");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "两次输入的新密码不一致");
        }

        // ========== 3. 取当前登录用户 + 限流 ==========
        Long userId = currentUserId();
        checkPasswordRateLimit(userId, request);

        // ========== 4. 加载账号并校验状态 ==========
        // 顺带拿到当前密码哈希，第 5、6 步都要用
        User user = loadEnabledUser(userId);

        // ========== 5. 按途径校验凭证：三条路唯一不同的一步 ==========
        verifyPasswordProof(proof, proofValue, user);

        // ========== 6. 新密码不能与原密码相同 ==========
        // 必须排在验凭证之后：若提前判断，拿着有效令牌但不知道原密码的人
        // 就能把这个接口当成判定器，逐个猜原密码并靠这条提示确认猜中了。
        // 代价是验证码那条路会因为复核失败而浪费掉一条已消费的验证码（要重新取码），
        // 这是为堵住判定器付的代价。
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码不能与原密码相同");
        }

        // ========== 7. 落库：改密 + 令牌版本号 +1 ==========
        // 版本号取第 4 步刚读出来的行 +1：一次 UPDATE 同时改掉密码和版本号，
        // 不会出现「密码改了但版本号没动」的中间态。
        // 并发改密时两个请求可能读到同一个旧值、都写回同一个新值：旧令牌仍会被作废，
        // 不会漏掉哪一次，所以这里不需要额外加锁。
        int newTokenVersion = normalizeTokenVersion(user.getTokenVersion()) + 1;

        // 只存密文；updateTime 由 MyMetaObjectHandler 自动刷新，不需要手工 set
        boolean updated = userService.lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getPassword, passwordEncoder.encode(newPassword))
                .set(User::getTokenVersion, newTokenVersion)
                .update();

        if (!updated) {
            throw new BusinessException(ResultCode.ERROR, "修改密码失败，请稍后重试");
        }

        // ========== 8. 刷新令牌版本缓存 ==========
        // 网关校验读的就是这份缓存，不刷新的话旧令牌会拿着缓存里的旧版本号继续通过校验，
        // 等于白改一次密码。
        try {
            cacheTokenVersion(userId, newTokenVersion);
        } catch (Exception e) {
            // 刷新失败（如 Redis 抖动）：把 key 删掉比留着旧值安全——
            // 网关读不到会回查本服务的内部接口，回查读到的是库里的新版本号，旧令牌照样被拒。
            log.error("刷新令牌版本缓存失败, userId={}", userId, e);
            redisTemplate.delete(tokenVersionKey(userId));
        }

        // 9. 发送一条密码修改成功的通知
        //拼装服务间规定的消息对象
        NotificationEvent notificationEvent = new NotificationEvent();
        notificationEvent.setUserId(userId);
        notificationEvent.setType("system");
        notificationEvent.setTitle("账号密码修改通知");
        notificationEvent.setContent("该账号的密码于" + DateTime.now().toString("yyyy-MM-dd HH:mm:ss") + "修改成功");
        notificationEvent.setLevel(1);

        //发送消息到消息队列中
        rocketMQTemplate.convertAndSend("life-mind-notification:PASSWORD_CHANGED", notificationEvent);

        // 本次改密用的令牌也被一并作废（版本号对不上），改完需要重新登录，
        // 其他设备上的登录态同样失效——这正是「改密踢掉所有旧令牌」要的效果。
        log.info("修改密码成功, userId={}, 途径={}", userId, proofLabel);
        return Result.success();
    }

    /**
     * 按途径校验「是本人」：改密三条路唯一不同的一步。
     *
     * <p>手机 / 邮箱两路取的是账号「已绑定的」号码，而不是请求里传来的号码——
     * 请求里根本没有号码字段，客户端指定不了，也就不存在验了 A 号码却改 B 账号的可能。
     *
     * @param proof      证明方式
     * @param proofValue 原密码明文或 6 位验证码
     * @param user       当前登录用户，含已绑号码与密码哈希
     */
    private void verifyPasswordProof(PasswordProof proof, String proofValue, User user) {
        switch (proof) {
            case ORIGINAL -> {
                // 长度上限：BCrypt 校验的成本与输入长度无关，但拦住超长输入可以省掉一次无意义的哈希
                if (proofValue.length() > MAX_PASSWORD_LENGTH
                        || !passwordEncoder.matches(proofValue, user.getPassword())) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "原密码错误");
                }
            }
            case PHONE -> {
                String phone = user.getPhone();
                if (phone == null || phone.isBlank()) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "尚未绑定手机号，请先绑定");
                }
                if (!ValidateUtils.codeValidate(proofValue)) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "短信验证码格式不正确");
                }
                if (!captchaVerifier.verifyPhoneCode(phone, proofValue)) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "短信验证码错误或已失效");
                }
            }
            case EMAIL -> {
                String email = user.getEmail();
                if (email == null || email.isBlank()) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "尚未绑定邮箱，请先绑定");
                }
                if (!ValidateUtils.codeValidate(proofValue)) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱验证码格式不正确");
                }
                if (!captchaVerifier.verifyEmailCode(email, proofValue)) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱验证码错误或已失效");
                }
            }
        }
    }

    /**
     * 修改密码的限流：按 IP、按账号两层。
     *
     * <p>没有绑定接口那层「按目标号码」：改密用的号码由服务端从账号里取，
     * 请求里没有可传的号码，不存在拿别人号码试码的路径。
     * 原密码那条路也靠这两层挡爆破。
     *
     * @param userId  当前登录用户 ID
     * @param request 当前请求，用于解析客户端真实 IP
     */
    private void checkPasswordRateLimit(Long userId, HttpServletRequest request) {
        if (!rateLimiter.tryAcquireByIp(IpUtils.getClientIp(request), PASSWORD_IP_CAPACITY, PASSWORD_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
        if (!rateLimiter.tryAcquireByUser(userId, PASSWORD_USER_CAPACITY, PASSWORD_USER_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
    }

    /**
     * 修改昵称的限流：按 IP、按账号两层。
     *
     * <p>没有绑定接口那层「按目标号码」：昵称不是占用型资料，不存在拿别人的值试的路径。
     *
     * @param userId  当前登录用户 ID
     * @param request 当前请求，用于解析客户端真实 IP
     */
    private void checkNicknameRateLimit(Long userId, HttpServletRequest request) {
        if (!rateLimiter.tryAcquireByIp(IpUtils.getClientIp(request), NICKNAME_IP_CAPACITY, NICKNAME_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
        if (!rateLimiter.tryAcquireByUser(userId, NICKNAME_USER_CAPACITY, NICKNAME_USER_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
    }

    /**
     * 修改账户名的限流：按 IP、按账号两层。
     *
     * @param userId  当前登录用户 ID
     * @param request 当前请求，用于解析客户端真实 IP
     */
    private void checkUsernameRateLimit(Long userId, HttpServletRequest request) {
        if (!rateLimiter.tryAcquireByIp(IpUtils.getClientIp(request), USERNAME_IP_CAPACITY, USERNAME_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
        if (!rateLimiter.tryAcquireByUser(userId, USERNAME_USER_CAPACITY, USERNAME_USER_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
    }

    /**
     * 检查账户名的修改冷却：一年只能改一次。
     *
     * <p>上次修改时间在 {@code user.username_change_time}：为空表示从未改过（注册不算一次修改），
     * 随时可以改第一次。
     *
     * @param user 当前账号，取其上次修改账户名的时间
     * @param now  本次请求的时间
     */
    private void checkUsernameCooldown(User user, LocalDateTime now) {
        LocalDateTime lastChangeTime = user.getUsernameChangeTime();

        // 从没改过：不受冷却限制
        if (lastChangeTime == null) {
            return;
        }

        LocalDateTime nextChangeTime = lastChangeTime.plusYears(USERNAME_COOLDOWN_YEARS);
        if (now.isBefore(nextChangeTime)) {
            // 向上取整，不足一天也按一天算，免得出现「还需等待 0 天」这种没意义的提示
            long remainDays = (ChronoUnit.SECONDS.between(now, nextChangeTime) + 86399) / 86400;
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "账户名 " + USERNAME_COOLDOWN_YEARS + " 年内只能修改一次，还需等待 " + remainDays
                            + " 天，最早可于 " + nextChangeTime.toLocalDate() + " 修改");
        }
    }

    /**
     * 绑定 / 换绑手机号与邮箱的通用实现：四种组合共用这一条路径。
     *
     * <p>手机号与邮箱的差异只有文案、长度上限、格式正则、验证码类型、落库字段五处，
     * 绑定与换绑的差异只有一条前置条件（账号是否已经绑过这个资料），
     * 所以用 {@link ContactTarget} 与 {@link BindMode} 两个参数表达，不拆成四份实现。
     *
     * @param target  资料类型（手机号 / 邮箱）
     * @param mode    绑定方式（首次绑定 / 换绑）
     * @param value   待绑定的手机号 / 邮箱
     * @param code    短信 / 邮件验证码
     * @param request 当前请求，用于解析客户端真实 IP 做限流
     */
    private Result<Void> bindContact(ContactTarget target, BindMode mode, String value, String code,
                                     HttpServletRequest request) {

        boolean phone = target == ContactTarget.PHONE;
        String label = phone ? "手机号" : "邮箱";
        String codeLabel = phone ? "短信验证码" : "邮箱验证码";
        SFunction<User, String> field = phone ? User::getPhone : User::getEmail;
        int maxLength = phone ? MAX_PHONE_LENGTH : MAX_EMAIL_LENGTH;

        // ========== 1. 参数非空校验 ==========
        if (value == null || value.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, label + "不能为空");
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, codeLabel + "不能为空");
        }

        // 长度上限：在进正则之前拦截超长输入
        if (value.length() > maxLength) {
            throw new BusinessException(ResultCode.PARAM_ERROR, label + "长度超出限制");
        }

        // ========== 2. 格式校验 ==========
        boolean formatValid = phone ? ValidateUtils.phoneValidate(value) : ValidateUtils.emailValidate(value);
        if (!formatValid) {
            throw new BusinessException(ResultCode.PARAM_ERROR, label + "格式不正确");
        }
        if (!ValidateUtils.codeValidate(code)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, codeLabel + "格式不正确");
        }

        // ========== 3. 取当前登录用户 + 限流 ==========
        // 只操作网关注入的身份对应的用户，前端传什么用户 ID 都不认，
        // 否则登录用户可以改掉别人的手机号 / 邮箱
        Long userId = currentUserId();
        String rateLimitType = (mode == BindMode.BIND ? "bind:" : "rebind:") + (phone ? "phone" : "email");
        checkBindRateLimit(rateLimitType, value, userId, request);

        // ========== 4. 校验验证码 ==========
        // 本接口不做人机校验（图形验证码），只校验短信 / 邮件验证码。
        // 顺序刻意放在查重与前置条件之前：先证明这个号码确实归调用者所有，
        // 否则登录用户能靠「已被其他账号绑定」这条提示反推出任意号码是否注册过（防枚举）。
        boolean codeVerified = phone
                ? captchaVerifier.verifyPhoneCode(value, code)
                : captchaVerifier.verifyEmailCode(value, code);
        if (!codeVerified) {
            throw new BusinessException(ResultCode.PARAM_ERROR, codeLabel + "错误或已失效");
        }

        // ========== 5. 绑定与换绑的唯一区别：账号是否已经绑过这个资料 ==========
        User user = loadEnabledUser(userId);
        String bound = phone ? user.getPhone() : user.getEmail();
        if (mode == BindMode.BIND && bound != null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "已绑定" + label + "，请使用换绑接口");
        }
        if (mode == BindMode.REBIND && bound == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "尚未绑定" + label + "，请先使用绑定接口");
        }

        // ========== 6. 换绑：新资料不能与当前的一样 ==========
        // 同样放在验码之后：先证明号码归属，再给「和原来一样」这个结论，
        // 提示里不带任何注册信息，不会被拿来探测号码有没有被注册
        if (mode == BindMode.REBIND && value.equals(bound)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新" + label + "不能与当前" + label + "相同");
        }

        // ========== 7. 查重：拦「被其他账号占用」 ==========
        // 绑定是账号当前为空、换绑是上面已确认新旧值不同，所以查到的记录必定属于其他账号
        assertContactBindable(field, label, value);

        // ========== 8. 落库 ==========
        boolean updated;
        try {
            updated = userService.lambdaUpdate()
                    .eq(User::getId, userId)
                    .set(field, value)
                    .update();
        } catch (DuplicateKeyException e) {
            // 并发操作同一号码时上面的查重会同时通过，这里靠唯一索引兜底
            log.warn("{} {} 重复, userId={}", mode.getLabel(), label, userId);
            throw new BusinessException(ResultCode.PARAM_ERROR, "该" + label + "已被其他账号绑定");
        }

        if (!updated) {
            throw new BusinessException(ResultCode.ERROR, "绑定失败，请稍后重试");
        }

        // 成功后不重新签发令牌：手机号与邮箱都不参与 JWT 声明，账号权限也不随绑定变化

        // 9. 发送一条绑定 / 换绑成功的通知
        // 拼装服务间规定的消息对象，content 中号码 / 邮箱脱敏，避免隐私外泄
        NotificationEvent notificationEvent = new NotificationEvent();
        notificationEvent.setUserId(userId);
        notificationEvent.setType("system");
        notificationEvent.setTitle("账号" + mode.getLabel() + label + "通知");
        notificationEvent.setContent("您的账号已于" + DateTime.now().toString("yyyy-MM-dd HH:mm:ss")
                + mode.getLabel() + label + "为：" + (phone
                ? DesensitizeUtils.desensitizePhone(value)
                : DesensitizeUtils.desensitizeEmail(value)));
        notificationEvent.setLevel(1);

        //发送消息到消息队列中
        rocketMQTemplate.convertAndSend("life-mind-notification:BINDING_CHANGED", notificationEvent);

        return Result.success();
    }

    /**
     * 资料类型：手机号 / 邮箱。
     */
    private enum ContactTarget {
        PHONE,
        EMAIL
    }

    /**
     * 绑定方式：首次绑定 / 换绑。
     *
     * <p>两者只有一条前置条件不同（账号是否已经绑过该资料），
     * 所以共用一个 {@link #bindContact}，在接口层拆成四个入口。
     */
    private enum BindMode {

        BIND("绑定"),
        REBIND("换绑");

        /**
         * 用于打日志，区分是绑定还是换绑出的问题
         */
        @Getter
        private final String label;

        BindMode(String label) {
            this.label = label;
        }
    }

    /**
     * 改密的证明方式：原密码 / 短信验证码 / 邮箱验证码。
     *
     * <p>三者对应三个接口，差异只有 {@code changePassword} 里「怎么证明是本人」这一步，
     * 所以用一个枚举区分，而不是三份几乎相同的实现。
     */
    private enum PasswordProof {

        ORIGINAL("原密码"),
        PHONE("短信验证码"),
        EMAIL("邮箱验证码");

        /**
         * 用于拼参数校验的提示文案与打日志，如「原密码不能为空」
         */
        @Getter
        private final String label;

        PasswordProof(String label) {
            this.label = label;
        }
    }

    @Override
    public Result<String> getPermissionsByUserId(Long userId) {

        // ========== 1. 参数校验 ==========
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不合法");
        }

        // ========== 2. 校验用户存在且未被禁用 ==========
        // 这里不校验密码，属于网关到本服务的内网调用；但仍要校验账号状态，
        // 避免已禁用账号通过该接口拿到权限标识。
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ENABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }

        // ========== 3. 先读缓存 ==========
        // 网关判定缓存未命中才会调过来，但缓存可能刚被写入，
        // 也可能为空串（该用户确实没有权限），两种情况都直接返回。
        Object cached = redisTemplate.opsForValue().get(scopesKey(userId));
        if (cached != null) {
            return Result.success(cached.toString());
        }

        // ========== 4. 缓存未命中，回查数据库 ==========
        String scopes = loadScopesByIdentityIds(loadIdentityIds(userId));

        // 回填缓存，与登录时的 key、过期时间保持一致，
        // 避免网关每次请求都回查数据库。空串同样会被缓存。
        cacheScopes(userId, scopes);

        return Result.success(scopes);
    }

    @Override
    public String loadScopesByIdentityIds(Collection<Long> identityIds) {
        // 获取这些身份拥有的全部权限（SQL 用 DISTINCT 去重，空集合时天然返回空列表），
        // 再对权限标识过滤 null 并去重兜底
        return identityPermissionMapper.selectPermissionsByIdentityIds(identityIds)
                .stream()
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(","));
    }

    @Override
    public void cacheScopes(Long userId, String scopes) {
        redisTemplate.opsForValue().set(scopesKey(userId), scopes, USER_SCOPES_TTL);
    }

    @Override
    public Result<Integer> getTokenVersionByUserId(Long userId) {

        // ========== 1. 参数校验 ==========
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不合法");
        }

        // ========== 2. 校验用户存在且未被禁用 ==========
        // 这里不校验密码，属于网关到本服务的内网调用；但仍要校验账号状态，
        // 避免已禁用账号拿着旧令牌通过版本校验继续访问。
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ENABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }

        // ========== 3. 先读缓存 ==========
        // 与权限接口一样：网关判定缓存未命中才会调过来，但缓存可能刚被写入，
        // 命中就直接返回，不再查库。
        Integer cached = readCachedTokenVersion(userId);
        if (cached != null) {
            return Result.success(cached);
        }

        // ========== 4. 缓存未命中，用刚查出来的这一行回填 ==========
        // 回填后，网关同一个请求里接下来的读缓存就能命中，也不再需要回源
        int tokenVersion = normalizeTokenVersion(user.getTokenVersion());
        cacheTokenVersion(userId, tokenVersion);

        return Result.success(tokenVersion);
    }

    @Override
    public int getTokenVersion(Long userId) {
        // ========== 1. 先读缓存 ==========
        Integer cached = readCachedTokenVersion(userId);
        if (cached != null) {
            return cached;
        }

        // ========== 2. 缓存未命中，回查数据库并回填 ==========
        // 与网关回查走同一份缓存：这里回填后，网关那次读缓存就能命中
        User user = userService.getById(userId);
        int tokenVersion = normalizeTokenVersion(user == null ? null : user.getTokenVersion());
        cacheTokenVersion(userId, tokenVersion);

        return tokenVersion;
    }

    @Override
    public void cacheTokenVersion(Long userId, int tokenVersion) {
        // 存字符串而不是数字：网关用的是另一套 ReactiveRedisTemplate，
        // 值与权限缓存一样按字符串序列化，两边才能互相读到。
        redisTemplate.opsForValue()
                .set(tokenVersionKey(userId), String.valueOf(tokenVersion), USER_TOKEN_VERSION_TTL);
    }

    /**
     * 读令牌版本缓存。
     *
     * @return 缓存里的版本号；返回 null 表示「没缓存」或「缓存内容不是数字」，
     * 两种情况都由调用方按未命中处理，回查数据库重新回填。
     */
    private Integer readCachedTokenVersion(Long userId) {
        Object cached = redisTemplate.opsForValue().get(tokenVersionKey(userId));
        if (cached == null) {
            return null;
        }
        try {
            return Integer.valueOf(cached.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("令牌版本缓存内容不是数字, userId={}, value={}", userId, cached);
            return null;
        }
    }

    /**
     * 令牌版本号的空值兜底。
     *
     * <p>库里该列非空且默认 0，正常查不出 null；这里只是防止历史数据或极端情况下
     * 拿到 null 直接 NPE，兜底值取 0（与库默认值、新用户初始值一致）。
     */
    private int normalizeTokenVersion(Integer tokenVersion) {
        return tokenVersion == null ? 0 : tokenVersion;
    }

    /**
     * 取当前登录用户的 ID。
     *
     * <p>身份由网关注入的请求头经 {@code HeaderAuthenticationFilter} 还原进 SecurityContext，
     * 所以这里拿到的一定是「已通过网关认证」的用户；拿不到说明请求没带 Token 或没走网关，
     * 按未登录处理。
     *
     * @return 当前登录用户 ID
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof LifeMindUserDetails) {
                Long userId = ((LifeMindUserDetails) principal).getUserId();
                if (userId != null && userId > 0) {
                    return userId;
                }
            }
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
    }

    /**
     * 绑定手机号 / 邮箱的限流：按 IP、按账号、按目标号码三层。
     *
     * @param targetType    业务标识前缀，如 {@code bind:phone}
     * @param target        待绑定的手机号 / 邮箱
     * @param userId        当前登录用户 ID
     * @param request       当前请求，用于解析客户端真实 IP
     */
    private void checkBindRateLimit(String targetType, String target, Long userId, HttpServletRequest request) {
        // 按 IP 限流：挡住同一来源的高频请求
        if (!rateLimiter.tryAcquireByIp(IpUtils.getClientIp(request), BIND_IP_CAPACITY, BIND_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }

        // 按账号限流：正常用户不会短时间内反复绑同一个资料
        if (!rateLimiter.tryAcquireByUser(userId, BIND_USER_CAPACITY, BIND_USER_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }

        // 按目标号码限流：验证码只有 6 位，不限制的话登录用户可以拿别人的手机号 / 邮箱
        // 反复试码，试中就能把别人的号码绑到自己账号上
        if (!rateLimiter.tryAcquireByTarget(targetType, target, BIND_TARGET_CAPACITY, BIND_TARGET_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "尝试次数过多，请 1 分钟后再试");
        }
    }

    /**
     * 加载账号并校验其存在且未被禁用。
     *
     * <p>令牌有效期内账号可能已被注销或禁用，改资料前必须再确认一次；
     * 实体本身要用来判断「账号是否已经绑过手机号 / 邮箱」，所以直接返回给调用方。
     */
    private User loadEnabledUser(Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ENABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }
        return user;
    }

    /**
     * 校验手机号 / 邮箱没有被占用。
     *
     * <p>调用前已经排除了「目标就是当前账号已绑的那个」这一种情况（绑定要求账号当前为空，
     * 换绑要求新旧值不同），所以这里查到的任何一条记录都属于其他账号，一律拒绝。
     *
     * <p>用 list 而不是 one()：one() 在库中出现多条同号数据时会抛
     * {@code TooManyResultsException}，变成一个看不懂的 500，
     * 这里统一按「已被占用」拒绝更稳妥。
     *
     * @param field 待校验的字段（{@code user.phone} / {@code user.email}）
     * @param label 资料名，用于拼提示文案
     * @param value 待绑定的手机号 / 邮箱
     */
    private void assertContactBindable(SFunction<User, String> field, String label, String value) {
        List<User> owners = userService.lambdaQuery()
                .eq(field, value)
                .list();

        if (!owners.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该" + label + "已被其他账号绑定");
        }
    }

    /**
     * 查询用户绑定的全部身份 ID。
     */
    private List<Long> loadIdentityIds(Long userId) {
        return userIdentityService.list(
                new LambdaQueryWrapper<UserIdentity>()
                        .eq(UserIdentity::getUserId, userId)
        ).stream()
                .map(UserIdentity::getIdentityId)
                .collect(Collectors.toList());
    }

    /**
     * 拼接用户权限在 Redis 中的 key，登录、网关、回查三处必须一致。
     */
    private String scopesKey(Long userId) {
        return USER_KEY_PREFIX + userId + USER_SCOPES_KEY_SUFFIX;
    }

    /**
     * 拼接令牌版本号在 Redis 中的 key，登录、改密、网关校验、回查四处必须一致。
     */
    private String tokenVersionKey(Long userId) {
        return USER_KEY_PREFIX + userId + USER_TOKEN_VERSION_KEY_SUFFIX;
    }
}
