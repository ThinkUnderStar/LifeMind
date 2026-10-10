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

    /**
     * 根据用户 ID 查询该用户拥有的全部权限标识（已去重）。
     *
     * <p>内部接口，供网关在 Redis 中查不到用户权限时回查数据库使用。
     * 返回的是逗号分隔的权限标识串，与登录时写入 Redis 的格式一致；
     * 用户存在但没有任何权限时返回空串（而不是 null），
     * 这样网关可以区分「查过了，确实没权限」和「还没查过」。
     *
     * @param userId 用户 ID
     * @return 权限标识串，多个用英文逗号分隔，无权限时为空串
     */
    Result<String> getPermissionsByUserId(Long userId);
}
