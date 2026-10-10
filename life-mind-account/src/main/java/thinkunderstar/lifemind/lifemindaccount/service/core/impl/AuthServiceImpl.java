package thinkunderstar.lifemind.lifemindaccount.service.core.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thinkunderstar.lifemind.lifemindaccount.entity.Permission;
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
import thinkunderstar.lifemind.lifemindaccount.entity.User;
import thinkunderstar.lifemind.lifemindaccount.entity.UserIdentity;
import thinkunderstar.lifemind.lifemindaccount.mapper.IdentityPermissionMapper;
import thinkunderstar.lifemind.lifemindaccount.service.core.AuthService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.IdentityService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserIdentityService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserService;
import thinkunderstar.lifemind.lifemindaccount.util.CaptchaVerifier;
import thinkunderstar.lifemind.lifemindaccount.util.CodeUtils;
import thinkunderstar.lifemind.lifemindaccount.util.IpUtils;
import thinkunderstar.lifemind.lifemindaccount.util.MailUtils;
import thinkunderstar.lifemind.lifemindaccount.util.RedisTokenBucketLimiter;
import thinkunderstar.lifemind.lifemindaccount.util.SmsUtils;
import thinkunderstar.lifemind.lifemindaccount.util.ValidateUtils;

import java.time.Duration;
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
     * 注册限流：单 IP 桶容量 10、每秒补 1 个（允许连打 10 次，之后平均 1 次/秒）；
     * 同一用户名桶容量 3、每 20 秒补 1 个，避免被拿来批量试探用户名是否已存在。
     */
    private static final long REGISTER_IP_CAPACITY = 10L;
    private static final double REGISTER_IP_RATE = 1.0;
    private static final long REGISTER_USERNAME_CAPACITY = 3L;
    private static final double REGISTER_USERNAME_RATE = 0.05;

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
     * 短信 / 邮件验证码在 Redis 中的 key 前缀，完整 key 形如
     * {@code sms:code:13800000000}、{@code email:code:a@b.com}。
     * 写入与校验两处必须用同一前缀，否则验证码永远校验不过。
     */
    private static final String SMS_CODE_KEY_PREFIX = "sms:code:";
    private static final String EMAIL_CODE_KEY_PREFIX = "email:code:";

    /**
     * 用户状态：1-正常（0 为禁用）
     */
    private static final int USER_STATUS_ENABLED = 1;

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
     * 用户权限在 Redis 中的 key 前缀，完整 key 为 {@code life-mind:{userId}:scopes}。
     * 登录时写入、网关读取、本服务回查后回填，三处必须保持一致。
     */
    private static final String USER_SCOPES_KEY_PREFIX = "life-mind:";

    /**
     * 权限缓存有效期，与登录时写入的保持一致。
     */
    private static final Duration USER_SCOPES_TTL = Duration.ofDays(7);

    private final IdentityPermissionMapper identityPermissionMapper;
    private final RedisTemplate<Object, Object> redisTemplate;

    /**
     * 短信 / 邮件验证码使用字符串序列化写入，必须与 {@link CaptchaVerifier} 读取时保持一致，
     * 否则会出现「写进去了但读不到」的情况。
     */
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${jwt.key}")
    private String jwtKey;

    private final RedisTokenBucketLimiter rateLimiter;
    private final UserService userService;
    private final UserIdentityService userIdentityService;
    private final IdentityService identityService;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaVerifier captchaVerifier;

    public AuthServiceImpl(RedisTokenBucketLimiter rateLimiter, UserService userService, UserIdentityService userIdentityService, IdentityService identityService, PasswordEncoder passwordEncoder, IdentityPermissionMapper identityPermissionMapper, RedisTemplate<Object, Object> redisTemplate, StringRedisTemplate stringRedisTemplate, CaptchaVerifier captchaVerifier) {
        this.rateLimiter = rateLimiter;
        this.userService = userService;
        this.userIdentityService = userIdentityService;
        this.identityService = identityService;
        this.passwordEncoder = passwordEncoder;
        this.identityPermissionMapper = identityPermissionMapper;
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.captchaVerifier = captchaVerifier;
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
        String nickname = registerRequest.getNickname();

        if (username == null || username.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码不能为空");
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

        // ========== 9. 注册完成 ==========
        // 这里不签发令牌：注册成功后由前端跳转到登录页，用户重新登录
        return Result.success();
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
        // 长度上限：验证码本身只有几位，超长输入直接判错，不做无谓的 Redis 查询
        if (tempKey.length() > MAX_USERNAME_LENGTH || captchaCode.length() > MAX_CAPTCHA_LENGTH) {
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
        return Result.success(issueLoginToken(user, rememberMe));
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
        // 这里把验证码的 Redis key 当作临时 key 传进去。
        if (!captchaVerifier.verify(smsCodeKey(phone), code)) {
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
        return Result.success(issueLoginToken(user, rememberMe));
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
        if (!captchaVerifier.verify(emailCodeKey(email), code)) {
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
        return Result.success(issueLoginToken(user, rememberMe));
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
                smsCodeKey(phone),
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
                emailCodeKey(email),
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
        // 长度上限：验证码本身只有几位，超长输入直接判错，不做无谓的 Redis 查询
        if (tempKey.length() > MAX_USERNAME_LENGTH || captchaCode.length() > MAX_CAPTCHA_LENGTH) {
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
     * 查身份与权限 → 权限写入 Redis 供网关读取 → 签 JWT。
     *
     * @param user       已通过认证的用户
     * @param rememberMe 1-记住我 0-不记住我
     * @return 登录响应
     */
    private LoginResponse issueLoginToken(User user, int rememberMe) {
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

        //获取权限信息并存入redis中
        String scopes = identityPermissionMapper.selectPermissionsByIdentityIds(identityIds)
                .stream()
                .map(Permission::getCode)
                .collect(Collectors.joining(","));
        redisTemplate.opsForValue().set(
                scopesKey(user.getId()),
                scopes,
                USER_SCOPES_TTL
        );

        //生成JWT
        String jwtToken = JWT.create()
                .withClaim("LifeMind-User-Id", user.getId())
                .withClaim("LifeMind-User-Roles", roles)
                .withExpiresAt(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30)))
                .withIssuedAt(new Date(System.currentTimeMillis()))
                .withIssuer("LifeMind")
                .withJWTId(UUID.randomUUID().toString())
                .sign(Algorithm.HMAC256(jwtKey));

        //封装返回对象，并返回给前端
        return new LoginResponse(jwtToken, rememberMe);
    }

    /**
     * 短信验证码在 Redis 中的 key。
     */
    private String smsCodeKey(String phone) {
        return SMS_CODE_KEY_PREFIX + phone;
    }

    /**
     * 邮件验证码在 Redis 中的 key。
     */
    private String emailCodeKey(String email) {
        return EMAIL_CODE_KEY_PREFIX + email;
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
        String scopes = loadScopesFromDb(userId);

        // 回填缓存，与登录时的 key、过期时间保持一致，
        // 避免网关每次请求都回查数据库。空串同样会被缓存。
        redisTemplate.opsForValue().set(scopesKey(userId), scopes, USER_SCOPES_TTL);

        return Result.success(scopes);
    }

    /**
     * 从数据库加载用户的权限标识串。
     *
     * <p>链路：user_identity 查身份 → identity_permission 关联 permission 查权限。
     * 权限按权限 ID 去重，防止同一权限被多个身份重复授予时返回重复项。
     *
     * @param userId 用户 ID
     * @return 权限标识串，无权限时为空串
     */
    private String loadScopesFromDb(Long userId) {
        //获取该用户的所有身份ID
        List<Long> identityIds = userIdentityService.list(
                new LambdaQueryWrapper<UserIdentity>()
                        .eq(UserIdentity::getUserId, userId)
        ).stream()
                .map(UserIdentity::getIdentityId)
                .collect(Collectors.toList());

        if (identityIds.isEmpty()) {
            return "";
        }

        //获取这些身份拥有的全部权限（SQL 用 DISTINCT 去重），再对权限标识去重兜底
        return identityPermissionMapper.selectPermissionsByIdentityIds(identityIds)
                .stream()
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(","));
    }

    /**
     * 拼接用户权限在 Redis 中的 key，登录、网关、回查三处必须一致。
     */
    private String scopesKey(Long userId) {
        return USER_SCOPES_KEY_PREFIX + userId + ":scopes";
    }
}
