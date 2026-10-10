package thinkunderstar.lifemind.lifemindaccount.service.core.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindaccount.entity.Permission;
import thinkunderstar.lifemind.lifemindaccount.exception.BusinessException;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.common.ResultCode;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PasswordLoginRequest;
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
import thinkunderstar.lifemind.lifemindaccount.util.IpUtils;
import thinkunderstar.lifemind.lifemindaccount.util.RedisTokenBucketLimiter;
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
     * 用户状态：1-正常（0 为禁用）
     */
    private static final int USER_STATUS_ENABLED = 1;

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

    @Value("${jwt.key}")
    private String jwtKey;

    private final RedisTokenBucketLimiter rateLimiter;
    private final UserService userService;
    private final UserIdentityService userIdentityService;
    private final IdentityService identityService;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaVerifier captchaVerifier;

    public AuthServiceImpl(RedisTokenBucketLimiter rateLimiter, UserService userService, UserIdentityService userIdentityService, IdentityService identityService, PasswordEncoder passwordEncoder, IdentityPermissionMapper identityPermissionMapper, RedisTemplate<Object, Object> redisTemplate, CaptchaVerifier captchaVerifier) {
        this.rateLimiter = rateLimiter;
        this.userService = userService;
        this.userIdentityService = userIdentityService;
        this.identityService = identityService;
        this.passwordEncoder = passwordEncoder;
        this.identityPermissionMapper = identityPermissionMapper;
        this.redisTemplate = redisTemplate;
        this.captchaVerifier = captchaVerifier;
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
        int rememberMe = passwordLoginRequest.getRememberMe();
        if (rememberMe != 0 && rememberMe != 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "记住我参数不合法");
        }

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
        return Result.success(new LoginResponse(jwtToken,passwordLoginRequest.getRememberMe()));
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
