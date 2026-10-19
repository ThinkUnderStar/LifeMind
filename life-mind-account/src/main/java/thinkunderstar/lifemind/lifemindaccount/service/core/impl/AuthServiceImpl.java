package thinkunderstar.lifemind.lifemindaccount.service.core.impl;

import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thinkunderstar.lifemind.lifemindaccount.exception.BusinessException;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.common.ResultCode;
import thinkunderstar.lifemind.lifemindaccount.dto.request.EmailCodeLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.EmailCodeSendRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PasswordLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PhoneCodeLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PhoneCodeSendRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.RegisterRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.response.LoginResponse;
import thinkunderstar.lifemind.lifemindaccount.entity.Identity;
import thinkunderstar.lifemind.lifemindaccount.entity.LoginAudit;
import thinkunderstar.lifemind.lifemindaccount.entity.User;
import thinkunderstar.lifemind.lifemindaccount.entity.UserIdentity;
import thinkunderstar.lifemind.lifemindaccount.event.NotificationEvent;
import thinkunderstar.lifemind.lifemindaccount.security.LifeMindUserDetails;
import thinkunderstar.lifemind.lifemindaccount.service.core.AuthService;
import thinkunderstar.lifemind.lifemindaccount.service.core.UserInfoService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.IdentityService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.LoginAuditService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserIdentityService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserService;
import thinkunderstar.lifemind.lifemindaccount.util.CaptchaVerifier;
import thinkunderstar.lifemind.lifemindaccount.util.CodeUtils;
import thinkunderstar.lifemind.lifemindaccount.util.IpRegionUtils;
import thinkunderstar.lifemind.lifemindaccount.util.IpUtils;
import thinkunderstar.lifemind.lifemindaccount.util.MailUtils;
import thinkunderstar.lifemind.lifemindaccount.util.RedisTokenBucketLimiter;
import thinkunderstar.lifemind.lifemindaccount.util.SmsUtils;
import thinkunderstar.lifemind.lifemindaccount.util.ValidateUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    /**
     * 单 IP 限流：桶容量 20（允许的突发次数），每秒补充 1 个令牌。
     * 即同一 IP 最多连打 20 次，之后平均 1 次/秒。
     */
    private static final long LOGIN_IP_CAPACITY = 20L;
    private static final double LOGIN_IP_RATE = 1.0;

    /**
     * 单账号限流：桶容量 5（允许的突发次数），每 5 秒补充 1 个令牌（rate = 0.2）。
     * 用来挡住针对某个账号的密码暴力破解。
     */
    private static final long LOGIN_USER_CAPACITY = 5L;
    private static final double LOGIN_USER_RATE = 0.2;

    /**
     * 用户名 / 密码长度硬上限，用于挡住超长字符串（正则回溯、内存放大）攻击，
     * 比正则更早拦截，避免把超长内容送进正则引擎。
     */
    private static final int MAX_USERNAME_LENGTH = 32;
    private static final int MAX_PASSWORD_LENGTH = 64;

    /**
     * 图形验证码长度上限。验证码本身只有几位，这里给足余量，
     * 只用于拦住超长输入，避免把无意义的长字符串送进 Redis 查询。
     */
    private static final int MAX_CAPTCHA_LENGTH = 16;

    /**
     * 人机验证临时 key 长度上限。
     * 前端用 crypto.randomUUID() 生成，是标准 8-4-4-4-12 的带连字符 UUID，
     * 固定 36 位（ValidateUtils.UUID_REGEX 也只认这种带连字符的写法）。
     * 这里同样只用于拦截超长输入，避免把无意义的长字符串当 Redis key 去查。
     */
    private static final int MAX_TEMP_KEY_LENGTH = 36;

    /**
     * 注册限流：单 IP 桶容量 10、每秒补 1 个（允许连打 10 次，之后平均 1 次/秒）；
     * 同一用户名桶容量 3、每 20 秒补 1 个，避免被拿来批量试探用户名是否已存在。
     */
    private static final long REGISTER_IP_CAPACITY = 10L;
    private static final double REGISTER_IP_RATE = 1.0;
    private static final long REGISTER_USERNAME_CAPACITY = 3L;
    private static final double REGISTER_USERNAME_RATE = 0.05;

    /**
     * 注销账号的限流：按 IP、按账号两层。
     *
     * <p>额度与改密同一档，避免高频重放注销请求；注销是登录态内的高敏操作，
     * 不需要「按目标」那一层（不认请求里的号码），按 IP + 按账号足够。
     */
    private static final long DEACTIVATE_IP_CAPACITY = 10L;
    private static final double DEACTIVATE_IP_RATE = 0.2;
    private static final long DEACTIVATE_USER_CAPACITY = 5L;
    private static final double DEACTIVATE_USER_RATE = 0.05;

    /**
     * 权限缓存的 key 前后缀，完整 key 为 {@code life-mind:{userId}:scopes}，
     * 与 {@code UserInfoServiceImpl} 中登录写缓存、网关读取时保持一致，注销时据此清除。
     */
    private static final String SCOPES_CACHE_KEY_PREFIX = "life-mind:";
    private static final String SCOPES_CACHE_KEY_SUFFIX = ":scopes";

    /**
     * 手机 / 邮箱长度硬上限，用于在进正则之前拦住超长输入。
     */
    private static final int MAX_PHONE_LENGTH = 11;
    private static final int MAX_EMAIL_LENGTH = 64;

    /**
     * 昵称长度上限，与 {@code ValidateUtils} 里昵称正则的上限保持一致。
     */
    private static final int MAX_NICKNAME_LENGTH = 16;

    /**
     * 发送验证码的限流：
     * 单 IP 桶容量 10、每秒补 0.2 个（即平均 5 秒 1 次）；
     * 单手机号 / 单邮箱桶容量 1、每 60 秒补 1 个，即同一目标 1 分钟只能发一次。
     */
    private static final long SEND_CODE_IP_CAPACITY = 10L;
    private static final double SEND_CODE_IP_RATE = 0.2;
    private static final long SEND_CODE_TARGET_CAPACITY = 1L;
    private static final double SEND_CODE_TARGET_RATE = 1.0 / 60;

    /**
     * 短信 / 邮件验证码在 Redis 中的有效期。
     */
    private static final long CODE_TTL_MINUTES = 5L;

    /**
     * 用户状态：1-正常（0 为禁用）
     */
    private static final int USER_STATUS_ENABLED = 1;

    /**
     * 新用户的令牌版本号，与 {@code user.token_version} 的库默认值保持一致。
     */
    private static final int DEFAULT_TOKEN_VERSION = 0;

    /**
     * 身份状态：1-正常（0 为禁用）
     */
    private static final int IDENTITY_STATUS_ENABLED = 1;

    /**
     * 注册时默认绑定的身份标识。
     *
     * <p>权限通过身份间接获得（用户 → 身份 → 权限），新用户统一绑普通用户身份，
     * 想要更高权限由管理员后续分配。身份不存在或已禁用时注册直接失败。
     */
    private static final String DEFAULT_IDENTITY_CODE = "ROLE_USER";

    /**
     * 短信 / 邮件验证码使用字符串序列化写入，必须与 {@link CaptchaVerifier} 读取时保持一致，
     * 否则会出现「写进去了但读不到」的情况。
     */
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<Object, Object> redisTemplate;

    @Value("${jwt.key}")
    private String jwtKey;

    private final RedisTokenBucketLimiter rateLimiter;
    private final UserService userService;
    private final UserIdentityService userIdentityService;
    private final IdentityService identityService;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaVerifier captchaVerifier;

    /**
     * 登录审计：登录/注册/注销时落一条审计记录（账号注销后记录仍保留）。
     */
    private final LoginAuditService loginAuditService;

    /**
     * RocketMQ 模板：向通知服务投递事件消息。
     */
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 用户权限的查询与缓存统一由它负责：登录时写缓存、网关回查时读缓存/回查数据库，
     * 两条路径共用同一份 Redis key 与过期时间，避免 key 拼接在多处各存一份。
     */
    private final UserInfoService userInfoService;

    public AuthServiceImpl(RedisTokenBucketLimiter rateLimiter, UserService userService, UserIdentityService userIdentityService, IdentityService identityService, PasswordEncoder passwordEncoder, StringRedisTemplate stringRedisTemplate, CaptchaVerifier captchaVerifier, LoginAuditService loginAuditService, RocketMQTemplate rocketMQTemplate, UserInfoService userInfoService, RedisTemplate<Object, Object> redisTemplate) {
        this.rateLimiter = rateLimiter;
        this.userService = userService;
        this.userIdentityService = userIdentityService;
        this.identityService = identityService;
        this.passwordEncoder = passwordEncoder;
        this.stringRedisTemplate = stringRedisTemplate;
        this.captchaVerifier = captchaVerifier;
        this.loginAuditService = loginAuditService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.userInfoService = userInfoService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 注册要写两张表（user + user_identity），放在同一个事务里：
     * 绑定身份失败时用户行一起回滚，不会留下没有身份的半成品账号。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> register(RegisterRequest registerRequest, HttpServletRequest request) {

        // ========== 1. 参数非空校验 ==========
        if (registerRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }

        String username = registerRequest.getUsername();
        String password = registerRequest.getPassword();
        String confirmPassword = registerRequest.getConfirmPassword();
        String nickname = registerRequest.getNickname();

        if (username == null || username.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码不能为空");
        }
        if (confirmPassword == null || confirmPassword.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "确认密码不能为空");
        }
        if (nickname == null || nickname.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "昵称不能为空");
        }

        // 长度上限：在进正则之前拦截超长输入
        if (username.length() > MAX_USERNAME_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名或密码长度超出限制");
        }

        // ========== 2. 格式校验（复用 ValidateUtils） ==========
        if (!ValidateUtils.usernameValidate(username)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名格式不正确");
        }
        if (!ValidateUtils.passwordValidate(password)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码格式不正确");
        }
        // 二次确认密码：必须与密码完全一致（放在格式校验之后，让「密码本身不合法」优先报错）
        if (!password.equals(confirmPassword)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "两次输入的密码不一致");
        }

        // 昵称：同样先拦超长进正则；只放行中文、字母、数字、下划线、连字符，
        // < > ' " & ; \ 等危险字符一律不通过（防脚本注入）
        if (nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "昵称长度超出限制");
        }
        if (!ValidateUtils.nicknameValidate(nickname)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "昵称格式不正确");
        }

        // ========== 3. 限流 ==========
        // 3.1 按 IP 限流：挡住来自同一来源的批量注册
        if (!rateLimiter.tryAcquireByIp(IpUtils.getClientIp(request), REGISTER_IP_CAPACITY, REGISTER_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }

        // 3.2 按用户名限流：挡住拿同一个名字反复试探
        if (!rateLimiter.tryAcquireByTarget("register", username, REGISTER_USERNAME_CAPACITY, REGISTER_USERNAME_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "尝试次数过多，请稍后再试");
        }

        // ========== 4. 校验图形验证码（人机校验） ==========
        // 放在查库之前：验证码不过就没必要查库，也顺带挡住脚本批量刷注册
        verifyHumanCaptcha(registerRequest.getTempKey(), registerRequest.getCaptchaCode());

        // ========== 5. 用户名查重 ==========
        // deleted（逻辑删除）由 @TableLogic 自动追加条件，
        // 已注销用户不参与查重，其用户名可以被重新注册
        boolean usernameExists = userService.lambdaQuery()
                .eq(User::getUsername, username)
                .exists();

        if (usernameExists) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名已存在");
        }

        // ========== 6. 取默认身份 ==========
        // 先取身份再落库：身份没配好就直接失败，不会留下一个没有任何身份的空壳用户
        Identity defaultIdentity = loadDefaultIdentity();

        // ========== 7. 落库 ==========
        User user = new User();
        user.setUsername(username);
        user.setNickname(nickname);
        // 只存 BCrypt 密文，明文既不落库也不打日志
        user.setPassword(passwordEncoder.encode(password));
        // 状态 1-正常；deleted 显式给 0，避免库中该列没有默认值时插入失败
        user.setStatus(USER_STATUS_ENABLED);
        user.setDeleted(0);
        // 令牌版本号从 0 开始，与库中该列的默认值一致；改密时 +1
        user.setTokenVersion(DEFAULT_TOKEN_VERSION);

        try {
            userService.save(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同一用户名时上面的查重会同时通过，这里靠唯一索引兜底
            log.warn("注册用户名重复: {}", username);
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名已存在");
        }

        // ========== 8. 绑定默认身份（权限随之而来） ==========
        // 权限不直接绑到用户身上，链路是「用户 → 身份 → 权限」：
        // 这里插一条 user_identity 绑定 ROLE_USER，
        // 登录时再按身份查出 identity_permission 里的权限写进 Redis，
        // 用户就拿到了普通用户该有的权限。
        UserIdentity userIdentity = new UserIdentity();
        userIdentity.setUserId(user.getId());
        userIdentity.setIdentityId(defaultIdentity.getId());
        userIdentityService.save(userIdentity);

        // ========== 9. 添加注册记录，并发送注册成功的信息 ==========

        // 9.1 登录审计：记录本次注册的 IP、设备等信息（账号注销后记录仍保留半年）
        String clientIp = IpUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        LoginAudit loginAudit = new LoginAudit();
        loginAudit.setUserId(user.getId());
        loginAudit.setType(LoginAudit.TYPE_REGISTER);
        loginAudit.setIp(clientIp);
        loginAudit.setRegion(IpRegionUtils.getRegion(clientIp));
        loginAudit.setUserAgent(userAgent);
        loginAudit.setDeviceType(parseDeviceType(userAgent));
        loginAuditService.save(loginAudit);

        // 9.2 发送注册成功通知：走 RocketMQ 投递给通知服务落库分发（与改密/换绑通知同一链路）
        NotificationEvent notificationEvent = new NotificationEvent();
        notificationEvent.setUserId(user.getId());
        notificationEvent.setType("system");
        notificationEvent.setTitle("注册成功通知");
        notificationEvent.setContent("欢迎加入 LifeMind！您的账号：" + username + " 于"
                + cn.hutool.core.date.DateTime.now().toString("yyyy-MM-dd HH:mm:ss") + "注册成功");
        notificationEvent.setLevel(1);
        rocketMQTemplate.convertAndSend("life-mind-notification:REGISTERED", notificationEvent);

        log.info("注册成功, userId={}, username={}, ip={}", user.getId(), username, clientIp);

        // ========== 10. 注册完成 ==========
        // 这里不签发令牌：注册成功后由前端跳转到登录页，用户重新登录
        return Result.success();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deactivateAccount(HttpServletRequest request) {

        // ========== 1. 取当前登录用户 ==========
        // 注销是高敏操作，身份必须来自网关注入并经 HeaderAuthenticationFilter 还原进安全上下文
        Long userId = currentUserId();

        // ========== 2. 限流 ==========
        // 2.1 按 IP 限流：挡住同一来源的高频注销
        if (!rateLimiter.tryAcquireByIp(IpUtils.getClientIp(request), DEACTIVATE_IP_CAPACITY, DEACTIVATE_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
        // 2.2 按账号限流：正常用户不会反复注销同一个账号
        if (!rateLimiter.tryAcquireByUser(userId, DEACTIVATE_USER_CAPACITY, DEACTIVATE_USER_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }

        // ========== 3. 加载账号并校验状态 ==========
        // 顺带拿到当前令牌版本号，下面软删除时要一起 +1
        User user = loadEnabledUser(userId);
        int newTokenVersion = normalizeTokenVersion(user.getTokenVersion()) + 1;

        // ========== 4. 软删除 + 解绑身份 + 令牌版本号 +1 ==========
        // 逻辑删除 user 行（deleted=1），并解绑该用户全部身份关系；
        // token_version +1 使 JWT 里的旧版本号对不上，此前签发的所有登录态一并作废。
        userService.lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getDeleted, 1)
                .set(User::getTokenVersion, newTokenVersion)
                .update();
        userIdentityService.lambdaUpdate()
                .eq(UserIdentity::getUserId, userId)
                .remove();

        // ========== 5. 清缓存 ==========
        // 5.1 刷新令牌版本缓存：不刷新的话网关读到的还是旧版本号，会继续放行旧令牌
        userInfoService.cacheTokenVersion(userId, newTokenVersion);
        // 5.2 删除权限缓存：账号已注销，不再让网关命中这份残留权限
        stringRedisTemplate.delete(SCOPES_CACHE_KEY_PREFIX + userId + SCOPES_CACHE_KEY_SUFFIX);

        // ========== 6. 落一条注销审计 ==========
        // 注销不发任何消息，只留审计记录（type=注销，账号注销后记录仍保留半年）
        String clientIp = IpUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        LoginAudit loginAudit = new LoginAudit();
        loginAudit.setUserId(userId);
        loginAudit.setType(LoginAudit.TYPE_RETIRE);
        loginAudit.setIp(clientIp);
        loginAudit.setRegion(IpRegionUtils.getRegion(clientIp));
        loginAudit.setUserAgent(userAgent);
        loginAudit.setDeviceType(parseDeviceType(userAgent));
        loginAuditService.save(loginAudit);

        log.info("注销账号成功, userId={}, ip={}", userId, clientIp);
        return Result.success();
    }

    /**
     * 用 hutool 解析 User-Agent，判定设备类型：1-PC 2-移动端。
     * 注意：hutool 5.8.46 的 UserAgent 只提供 isMobile() 一个布尔判定，
     * 平板/爬虫等其余场景统一按 PC（1）处理。
     */
    private Integer parseDeviceType(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        UserAgent parsed = UserAgentUtil.parse(userAgent);
        if (parsed == null) {
            return null;
        }
        return parsed.isMobile() ? 2 : 1;
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
     * 加载账号并校验其存在且未被禁用。
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
     * 令牌版本号的空值兜底，与库默认值一致。
     */
    private int normalizeTokenVersion(Integer tokenVersion) {
        return tokenVersion == null ? DEFAULT_TOKEN_VERSION : tokenVersion;
    }

    /**
     * 读该用户上次登录地区缓存；无缓存或缓存内容为空时返回 null。
     *
     * <p>key 与 {@code issueLoginToken} 中写入本次登录地区时保持一致
     * （{@code life-mind-account:{userId}:last-login}）。
     */
    private String readLastLoginRegion(Long userId) {
        Object cached = redisTemplate.opsForValue()
                .get("life-mind-account:" + userId + ":last-login");
        if (cached == null) {
            return null;
        }
        String value = cached.toString();
        return value.isBlank() ? null : value;
    }

    @Override
    public Result<LoginResponse> passwordLogin(PasswordLoginRequest passwordLoginRequest, HttpServletRequest request) {

        // ========== 1. 参数非空校验 ==========
        if (passwordLoginRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }

        String username = passwordLoginRequest.getUsername();
        String password = passwordLoginRequest.getPassword();

        if (username == null || username.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码不能为空");
        }

        // 长度上限：在进正则之前拦截超长输入
        if (username.length() > MAX_USERNAME_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名或密码长度超出限制");
        }

        // rememberMe 只允许 0(不记住) / 1(记住)
        int rememberMe = validateRememberMe(passwordLoginRequest.getRememberMe());

        // ========== 2. 格式校验（复用 ValidateUtils） ==========
        if (!ValidateUtils.usernameValidate(username)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名格式不正确");
        }
        if (!ValidateUtils.passwordValidate(password)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码格式不正确");
        }

        // ========== 3. 限流 ==========
        // 3.1 先按 IP 限流：挡住来自同一来源的高频请求
        String clientIp = IpUtils.getClientIp(request);
        if (!rateLimiter.tryAcquireByIp(clientIp, LOGIN_IP_CAPACITY, LOGIN_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }

        // 3.2 再按账号限流：挡住针对单一账号的密码暴力破解
        if (!rateLimiter.tryAcquireByUser(username, LOGIN_USER_CAPACITY, LOGIN_USER_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "尝试次数过多，请 1 分钟后再试");
        }

        // ========== 4. 校验图形验证码 ==========
        // 放在查库和比对密码之前：验证码不过就没必要查库，
        // 也避免验证码形同虚设、密码依旧被暴力尝试。
        String tempKey = passwordLoginRequest.getTempKey();
        String captchaCode = passwordLoginRequest.getCaptchaCode();

        if (tempKey == null || tempKey.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "验证码临时Key不能为空");
        }
        if (captchaCode == null || captchaCode.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "验证码不能为空");
        }
        // 长度上限：tempKey 是 36 位 UUID、验证码只有几位，超长输入直接判错，不做无谓的 Redis 查询
        if (tempKey.length() > MAX_TEMP_KEY_LENGTH || captchaCode.length() > MAX_CAPTCHA_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "验证码参数超出限制");
        }

        if (!captchaVerifier.verify(tempKey, captchaCode)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "验证码错误或已失效");
        }

        // ========== 5. 查用户 + 核对密码 ==========
        // deleted（逻辑删除）由 @TableLogic 自动追加条件，这里只需按用户名查
        User user = userService.lambdaQuery()
                .eq(User::getUsername, username)
                .one();

        // 「用户不存在」与「密码错误」返回同一提示，避免暴露账号是否存在（防用户名枚举）
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 账号状态：0-禁用 1-正常，非正常一律拒绝
        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ENABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // ========== 6. 签发令牌 ==========
        return Result.success(issueLoginToken(user, rememberMe, request));
    }

    @Override
    public Result<LoginResponse> phoneCodeLogin(PhoneCodeLoginRequest phoneCodeLoginRequest, HttpServletRequest request) {

        // ========== 1. 参数非空校验 ==========
        if (phoneCodeLoginRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }

        String phone = phoneCodeLoginRequest.getPhone();
        String code = phoneCodeLoginRequest.getCode();

        if (phone == null || phone.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "手机号不能为空");
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "短信验证码不能为空");
        }

        // 长度上限：在进正则之前拦截超长输入
        if (phone.length() > MAX_PHONE_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "手机号长度超出限制");
        }

        // rememberMe 只允许 0(不记住) / 1(记住)
        int rememberMe = validateRememberMe(phoneCodeLoginRequest.getRememberMe());

        // ========== 2. 格式校验 ==========
        if (!ValidateUtils.phoneValidate(phone)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "手机号格式不正确");
        }
        if (!ValidateUtils.codeValidate(code)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "短信验证码格式不正确");
        }

        // ========== 3. 限流 ==========
        // 3.1 先按 IP 限流：挡住来自同一来源的高频请求
        if (!rateLimiter.tryAcquireByIp(IpUtils.getClientIp(request), LOGIN_IP_CAPACITY, LOGIN_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }

        // 3.2 再按手机号限流：挡住针对单一账号的验证码爆破
        if (!rateLimiter.tryAcquireByTarget("login:phone", phone, LOGIN_USER_CAPACITY, LOGIN_USER_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "尝试次数过多，请 1 分钟后再试");
        }

        // ========== 4. 校验图形验证码（人机校验） ==========
        verifyHumanCaptcha(phoneCodeLoginRequest.getTempKey(), phoneCodeLoginRequest.getCaptchaCode());

        // ========== 5. 校验短信验证码 ==========
        // 复用 CaptchaVerifier：同样是「一次性 + 限制试错次数」的校验语义，
        // 验证码的 Redis key 由 CaptchaVerifier 统一拼接，与下发时保持一致。
        if (!captchaVerifier.verifyPhoneCode(phone, code)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "短信验证码错误或已失效");
        }

        // ========== 6. 查用户 ==========
        // 「用户不存在」与「账号异常」对外都返回同一提示，避免暴露手机号是否注册（防枚举）
        User user = userService.lambdaQuery()
                .eq(User::getPhone, phone)
                .one();

        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "手机号或验证码错误");
        }

        // 账号状态：0-禁用 1-正常，非正常一律拒绝
        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ENABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }

        // ========== 7. 签发令牌 ==========
        return Result.success(issueLoginToken(user, rememberMe, request));
    }

    @Override
    public Result<LoginResponse> emailCodeLogin(EmailCodeLoginRequest emailCodeLoginRequest, HttpServletRequest request) {

        // ========== 1. 参数非空校验 ==========
        if (emailCodeLoginRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }

        String email = emailCodeLoginRequest.getEmail();
        String code = emailCodeLoginRequest.getCode();

        if (email == null || email.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱不能为空");
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱验证码不能为空");
        }

        // 长度上限：在进正则之前拦截超长输入
        if (email.length() > MAX_EMAIL_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱长度超出限制");
        }

        // rememberMe 只允许 0(不记住) / 1(记住)
        int rememberMe = validateRememberMe(emailCodeLoginRequest.getRememberMe());

        // ========== 2. 格式校验 ==========
        if (!ValidateUtils.emailValidate(email)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱格式不正确");
        }
        if (!ValidateUtils.codeValidate(code)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱验证码格式不正确");
        }

        // ========== 3. 限流 ==========
        // 3.1 先按 IP 限流
        if (!rateLimiter.tryAcquireByIp(IpUtils.getClientIp(request), LOGIN_IP_CAPACITY, LOGIN_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }

        // 3.2 再按邮箱限流
        if (!rateLimiter.tryAcquireByTarget("login:email", email, LOGIN_USER_CAPACITY, LOGIN_USER_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "尝试次数过多，请 1 分钟后再试");
        }

        // ========== 4. 校验图形验证码（人机校验） ==========
        verifyHumanCaptcha(emailCodeLoginRequest.getTempKey(), emailCodeLoginRequest.getCaptchaCode());

        // ========== 5. 校验邮件验证码 ==========
        if (!captchaVerifier.verifyEmailCode(email, code)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱验证码错误或已失效");
        }

        // ========== 6. 查用户 ==========
        // 同手机号登录：用户不存在时返回同一提示，避免暴露邮箱是否注册
        User user = userService.lambdaQuery()
                .eq(User::getEmail, email)
                .one();

        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "邮箱或验证码错误");
        }

        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ENABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }

        // ========== 7. 签发令牌 ==========
        return Result.success(issueLoginToken(user, rememberMe, request));
    }

    @Override
    public Result<Void> sendPhoneCode(PhoneCodeSendRequest phoneCodeSendRequest, HttpServletRequest request) {

        // ========== 1. 参数校验 ==========
        if (phoneCodeSendRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }

        String phone = phoneCodeSendRequest.getPhone();
        if (phone == null || phone.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "手机号不能为空");
        }
        if (phone.length() > MAX_PHONE_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "手机号长度超出限制");
        }
        if (!ValidateUtils.phoneValidate(phone)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "手机号格式不正确");
        }

        // ========== 2. 限流 ==========
        // 2.1 按 IP 限流：挡住刷接口
        if (!rateLimiter.tryAcquireByIp(IpUtils.getClientIp(request), SEND_CODE_IP_CAPACITY, SEND_CODE_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "发送过于频繁，请稍后再试");
        }

        // 2.2 按手机号限流：同一号码 1 分钟只能发一次，防止被当成短信轰炸机
        if (!rateLimiter.tryAcquireByTarget("send:sms", phone, SEND_CODE_TARGET_CAPACITY, SEND_CODE_TARGET_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "验证码已发送，请 1 分钟后再试");
        }

        // ========== 3. 生成验证码并写入 Redis ==========
        // 这里不校验手机号是否已注册：注册与否是登录时的事，
        // 发送环节不做判断，避免被拿来枚举手机号。
        String code = CodeUtils.getSixDigitCode();
        stringRedisTemplate.opsForValue().set(
                CaptchaVerifier.phoneCodeKey(phone),
                code,
                CODE_TTL_MINUTES,
                TimeUnit.MINUTES
        );

        // ========== 4. 下发验证码 ==========
        SmsUtils.sendCode(phone, code);

        return Result.success();
    }

    @Override
    public Result<Void> sendEmailCode(EmailCodeSendRequest emailCodeSendRequest, HttpServletRequest request) {

        // ========== 1. 参数校验 ==========
        if (emailCodeSendRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }

        String email = emailCodeSendRequest.getEmail();
        if (email == null || email.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱不能为空");
        }
        if (email.length() > MAX_EMAIL_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱长度超出限制");
        }
        if (!ValidateUtils.emailValidate(email)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱格式不正确");
        }

        // ========== 2. 限流 ==========
        if (!rateLimiter.tryAcquireByIp(IpUtils.getClientIp(request), SEND_CODE_IP_CAPACITY, SEND_CODE_IP_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "发送过于频繁，请稍后再试");
        }
        if (!rateLimiter.tryAcquireByTarget("send:email", email, SEND_CODE_TARGET_CAPACITY, SEND_CODE_TARGET_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "验证码已发送，请 1 分钟后再试");
        }

        // ========== 3. 生成验证码并写入 Redis ==========
        String code = CodeUtils.getSixDigitCode();
        stringRedisTemplate.opsForValue().set(
                CaptchaVerifier.emailCodeKey(email),
                code,
                CODE_TTL_MINUTES,
                TimeUnit.MINUTES
        );

        // ========== 4. 下发验证码 ==========
        MailUtils.sendCode(email, code);

        return Result.success();
    }

    /**
     * 读取注册时默认绑定的身份（ROLE_USER）。
     *
     * <p>身份不存在或已被禁用时直接让注册失败：否则会留下一个没有任何身份的用户，
     * 登录后所有需要权限的接口都是 403，比注册时报错更难排查。
     *
     * @return 默认身份
     */
    private Identity loadDefaultIdentity() {
        Identity identity = identityService.lambdaQuery()
                .eq(Identity::getCode, DEFAULT_IDENTITY_CODE)
                .one();

        if (identity == null || identity.getStatus() == null
                || identity.getStatus() != IDENTITY_STATUS_ENABLED) {
            log.error("注册默认身份未配置或已禁用, code={}", DEFAULT_IDENTITY_CODE);
            throw new BusinessException(ResultCode.ERROR, "系统默认身份未配置，请联系管理员");
        }

        return identity;
    }

    /**
     * 校验图形验证码（人机校验）。
     *
     * <p>校验不通过直接抛业务异常，调用方无需再判断返回值。
     *
     * @param tempKey     前端生成的临时 key
     * @param captchaCode 用户填写的图形验证码
     */
    private void verifyHumanCaptcha(String tempKey, String captchaCode) {
        if (tempKey == null || tempKey.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "图形验证码临时Key不能为空");
        }
        if (captchaCode == null || captchaCode.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "图形验证码不能为空");
        }
        // 长度上限：tempKey 是 36 位 UUID、验证码只有几位，超长输入直接判错，不做无谓的 Redis 查询
        if (tempKey.length() > MAX_TEMP_KEY_LENGTH || captchaCode.length() > MAX_CAPTCHA_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "图形验证码参数超出限制");
        }
        if (!captchaVerifier.verify(tempKey, captchaCode)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "图形验证码错误或已失效");
        }
    }

    /**
     * 校验「记住我」参数。
     *
     * @param rememberMe 1-记住我 0-不记住我
     * @return 校验通过的参数原值
     */
    private int validateRememberMe(int rememberMe) {
        if (rememberMe != 0 && rememberMe != 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "记住我参数不合法");
        }
        return rememberMe;
    }

    /**
     * 为已通过认证的用户签发令牌。
     *
     * <p>三种登录方式（密码、手机验证码、邮箱验证码）共用这一段：
     * 查身份与权限 → 权限写入 Redis 供网关读取 → 取令牌版本号 → 签 JWT → 落一条登录审计。
     *
     * @param user       已通过认证的用户
     * @param rememberMe 1-记住我 0-不记住我
     * @param request    当前请求，用于解析客户端真实 IP / User-Agent 做登录审计
     * @return 登录响应
     */
    private LoginResponse issueLoginToken(User user, int rememberMe, HttpServletRequest request) {
        //获取身份信息
        List<Long> identityIds = userIdentityService.list(
                new LambdaQueryWrapper<UserIdentity>()
                        .eq(UserIdentity::getUserId, user.getId())
        ).stream()
                .map(userIdentity -> userIdentity.getIdentityId())
                .collect(Collectors.toList());

        List<Identity> identities = identityService.listByIds(identityIds);
        String roles = identities.stream()
                .map(Identity::getCode)
                .collect(Collectors.joining(","));

        //获取权限信息并存入redis中（key 与过期时间由 UserInfoService 统一负责，
        //与网关回查时的读取必须完全一致）
        String scopes = userInfoService.loadScopesByIdentityIds(identityIds);
        userInfoService.cacheScopes(user.getId(), scopes);

        //取当前令牌版本号：同样是先读缓存、未命中才回查数据库并回填（由 UserInfoService 统一负责），
        //网关校验时读的是同一份缓存。改密会把版本号 +1，这里签发出来的下一个令牌才会带上新值
        int tokenVersion = userInfoService.getTokenVersion(user.getId());

        //生成JWT
        String jwtToken = JWT.create()
                .withClaim("LifeMind-User-Id", user.getId())
                .withClaim("LifeMind-User-Roles", roles)
                .withClaim("LifeMind-Token-Version", tokenVersion)
                .withExpiresAt(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30)))
                .withIssuedAt(new Date(System.currentTimeMillis()))
                .withIssuer("LifeMind")
                .withJWTId(UUID.randomUUID().toString())
                .sign(Algorithm.HMAC256(jwtKey));

        //落一条登录审计：三种登录方式共用此方法，所以登录记录只在这里统一写一份
        //（账号注销后记录仍保留半年）
        String clientIp = IpUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        String region = IpRegionUtils.getRegion(clientIp);
        LoginAudit loginAudit = new LoginAudit();
        loginAudit.setUserId(user.getId());
        loginAudit.setType(LoginAudit.TYPE_LOGIN);
        loginAudit.setIp(clientIp);
        loginAudit.setRegion(region);
        loginAudit.setUserAgent(userAgent);
        loginAudit.setDeviceType(parseDeviceType(userAgent));
        loginAuditService.save(loginAudit);

        //检查是否异地登录，如果是发送警告信息
        // 原则：拿本次登录地区对比「最近一次已知登录地区」，不同则判定异地，发一条安全提醒。
        // 地区为空（本机/内网 IP，ip2region 不收录）时无法比较，跳过检查。
        if (region != null) {
            // 1. 先走缓存：读上次登录地区
            String lastRegion = readLastLoginRegion(user.getId());
            if (lastRegion == null) {
                // 2. 缓存未命中，回查数据库取该用户最近一条审计记录的地区。
                //    若最近一次是注册记录（type=2，尚未产生过真正登录地区），忽略并跳过本次检查
                LoginAudit lastLogin = loginAuditService.lambdaQuery()
                        .eq(LoginAudit::getUserId, user.getId())
                        .orderByDesc(LoginAudit::getId)
                        .last("limit 1")
                        .one();
                if (lastLogin != null
                        && lastLogin.getType() != null
                        && lastLogin.getType() == LoginAudit.TYPE_LOGIN) {
                    lastRegion = lastLogin.getRegion();
                }
            }

            // 3. 两侧都有地区且不同，认定为异地登录，发一条安全提醒
            if (lastRegion != null && !region.equals(lastRegion)) {
                NotificationEvent warning = new NotificationEvent();
                warning.setUserId(user.getId());
                warning.setType("system");
                warning.setTitle("异地登录提醒");
                warning.setContent("您的账号于 "
                        + cn.hutool.core.date.DateTime.now().toString("yyyy-MM-dd HH:mm:ss")
                        + " 在 " + region + " 登录，若并非本人操作，请及时修改密码。");
                warning.setLevel(1);
                rocketMQTemplate.convertAndSend("life-mind-notification:LOGIN_WARNING", warning);
                log.warn("检测到异地登录, userId={}, 上次地区={}, 本次地区={}",
                        user.getId(), lastRegion, region);
            }
        }

        //将这次登录的地址存入redis，提高检查异地登录的性能
        redisTemplate.opsForValue().set(
                "life-mind-account:"+user.getId()+":last-login",
                region,30,
                TimeUnit.DAYS
        );

        log.info("用户登录成功, userId={}, ip={}", user.getId(), clientIp);

        //封装返回对象，并返回给前端
        return new LoginResponse(jwtToken, rememberMe);
    }
}
