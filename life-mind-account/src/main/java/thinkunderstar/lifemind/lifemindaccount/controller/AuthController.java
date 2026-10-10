package thinkunderstar.lifemind.lifemindaccount.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.dto.request.EmailCodeLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.EmailCodeSendRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PasswordLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PhoneCodeLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PhoneCodeSendRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.RegisterRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.response.LoginResponse;
import thinkunderstar.lifemind.lifemindaccount.service.core.AuthService;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 认证相关的接口。
     *
     * <p>提供登录、注册、验证码发送等认证入口，
     * 走网关的白名单，不需要携带 Token。
     */
    @PostMapping("/login/password")
    public Result<LoginResponse> passwordLogin(
            @RequestBody PasswordLoginRequest passwordLoginRequest,
            HttpServletRequest request
    ){
        return authService.passwordLogin(passwordLoginRequest, request);
    }

    /**
     * 注册。
     *
     * <p>参数为用户名、密码、昵称与图形验证码，密码用 BCrypt 加密后落库，
     * 昵称做了非空与字符集校验（挡掉 &lt; &gt; ' " 等危险字符）。
     * 注册成功会自动绑定默认身份 ROLE_USER；不签发令牌，前端应跳转到登录页让用户重新登录。
     */
    @PostMapping("/register")
    public Result<Void> register(
            @RequestBody RegisterRequest registerRequest,
            HttpServletRequest request
    ) {
        return authService.register(registerRequest, request);
    }

    /**
     * 手机验证码登录。
     *
     * <p>登录时同时校验图形验证码（人机校验）与短信验证码，
     * 两个验证码都必须先通过 {@code /captcha/get}、{@code /auth/code/send/phone} 获取。
     */
    @PostMapping("/login/phone")
    public Result<LoginResponse> phoneCodeLogin(
            @RequestBody PhoneCodeLoginRequest phoneCodeLoginRequest,
            HttpServletRequest request
    ) {
        return authService.phoneCodeLogin(phoneCodeLoginRequest, request);
    }

    /**
     * 邮箱验证码登录。
     *
     * <p>登录时同时校验图形验证码（人机校验）与邮件验证码，
     * 两个验证码都必须先通过 {@code /captcha/get}、{@code /auth/code/send/email} 获取。
     */
    @PostMapping("/login/email")
    public Result<LoginResponse> emailCodeLogin(
            @RequestBody EmailCodeLoginRequest emailCodeLoginRequest,
            HttpServletRequest request
    ) {
        return authService.emailCodeLogin(emailCodeLoginRequest, request);
    }

    /**
     * 发送短信验证码。
     *
     * <p>只做发送与限流，不做人机校验；图形验证码在登录时校验。
     */
    @PostMapping("/code/send/phone")
    public Result<Void> sendPhoneCode(
            @RequestBody PhoneCodeSendRequest phoneCodeSendRequest,
            HttpServletRequest request
    ) {
        return authService.sendPhoneCode(phoneCodeSendRequest, request);
    }

    /**
     * 发送邮件验证码。
     *
     * <p>只做发送与限流，不做人机校验；图形验证码在登录时校验。
     */
    @PostMapping("/code/send/email")
    public Result<Void> sendEmailCode(
            @RequestBody EmailCodeSendRequest emailCodeSendRequest,
            HttpServletRequest request
    ) {
        return authService.sendEmailCode(emailCodeSendRequest, request);
    }

    /**
     * 获取指定用户的权限标识（内部接口）。
     *
     * <p>供网关在 Redis 中查不到用户权限时回查数据库使用，
     * 已在 {@code SpringSecurityConfig} 与 {@code HeaderAuthenticationFilter} 中放行，
     * 由网关携带 {@code LifeMind-User-Id} 请求头调用，只允许内网访问。
     *
     * <p>返回的 data 是逗号分隔的权限标识串，与 {@code LifeMind-User-Scopes}
     * 请求头的格式一致；用户存在但无权限时 data 为空串。
     *
     * @param userId 用户 ID
     */
    @GetMapping("/internal/permissions")
    public Result<String> internalPermissions(@RequestParam("userId") Long userId) {
        return authService.getPermissionsByUserId(userId);
    }
}
