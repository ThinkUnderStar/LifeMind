package thinkunderstar.lifemind.lifemindaccount.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PasswordLoginRequest;
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
