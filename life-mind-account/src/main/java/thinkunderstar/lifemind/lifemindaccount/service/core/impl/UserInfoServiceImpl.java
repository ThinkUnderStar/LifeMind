package thinkunderstar.lifemind.lifemindaccount.service.core.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByEmailRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByOriginalRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByPhoneRequest;
import thinkunderstar.lifemind.lifemindaccount.entity.Permission;
import thinkunderstar.lifemind.lifemindaccount.entity.User;
import thinkunderstar.lifemind.lifemindaccount.entity.UserIdentity;
import thinkunderstar.lifemind.lifemindaccount.exception.BusinessException;
import thinkunderstar.lifemind.lifemindaccount.mapper.IdentityPermissionMapper;
import thinkunderstar.lifemind.lifemindaccount.security.LifeMindUserDetails;
import thinkunderstar.lifemind.lifemindaccount.service.core.UserInfoService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserIdentityService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserService;
import thinkunderstar.lifemind.lifemindaccount.util.CaptchaVerifier;
import thinkunderstar.lifemind.lifemindaccount.util.IpUtils;
import thinkunderstar.lifemind.lifemindaccount.util.RedisTokenBucketLimiter;
import thinkunderstar.lifemind.lifemindaccount.util.ValidateUtils;

import java.time.Duration;
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
     * 用户权限在 Redis 中的 key 前缀，完整 key 为 {@code life-mind:{userId}:scopes}。
     * 登录时写入、网关读取、本服务回查后回填，三处必须保持一致。
     */
    private static final String USER_SCOPES_KEY_PREFIX = "life-mind:";

    /**
     * 权限缓存有效期，与登录时写入的保持一致。
     */
    private static final Duration USER_SCOPES_TTL = Duration.ofDays(7);

    /**
     * 手机 / 邮箱长度硬上限，用于在进正则之前拦住超长输入，
     * 与 {@code AuthServiceImpl} 中发送验证码时的上限保持一致。
     */
    private static final int MAX_PHONE_LENGTH = 11;
    private static final int MAX_EMAIL_LENGTH = 64;

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

    public UserInfoServiceImpl(UserService userService,
                               UserIdentityService userIdentityService,
                               IdentityPermissionMapper identityPermissionMapper,
                               RedisTemplate<Object, Object> redisTemplate,
                               RedisTokenBucketLimiter rateLimiter,
                               CaptchaVerifier captchaVerifier,
                               PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.userIdentityService = userIdentityService;
        this.identityPermissionMapper = identityPermissionMapper;
        this.redisTemplate = redisTemplate;
        this.rateLimiter = rateLimiter;
        this.captchaVerifier = captchaVerifier;
        this.passwordEncoder = passwordEncoder;
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
     * <p>不涉及令牌：密码不参与 JWT 声明，改完也不重新签发，
     * 已经发出去的令牌继续有效（本次不引入失效机制）。
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

        // ========== 7. 落库 ==========
        // 只存密文；updateTime 由 MyMetaObjectHandler 自动刷新，不需要手工 set
        boolean updated = userService.lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getPassword, passwordEncoder.encode(newPassword))
                .update();

        if (!updated) {
            throw new BusinessException(ResultCode.ERROR, "修改密码失败，请稍后重试");
        }

        // 不重新签发令牌：密码不参与 JWT 声明，权限也不随密码变化。
        // 注意旧令牌不会因此失效（本次不引入凭证失效机制），其他设备仍可继续使用。
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
        return USER_SCOPES_KEY_PREFIX + userId + ":scopes";
    }
}
