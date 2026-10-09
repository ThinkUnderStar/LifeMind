package thinkunderstar.lifemind.lifemindaccount.service.core;

import jakarta.servlet.http.HttpServletRequest;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PasswordLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.response.LoginResponse;

public interface AuthService {
    /**
     * 认证相关的接口。
     *
     * <p>提供登录、注册、验证码发送等认证入口，
     * 走网关的白名单，不需要携带 Token。
     *
     * @param passwordLoginRequest 登录请求参数
     * @param request              当前请求，用于解析客户端真实 IP 做限流
     */
    Result<LoginResponse> passwordLogin(PasswordLoginRequest passwordLoginRequest, HttpServletRequest request);
}
