package thinkunderstar.lifemind.lifemindaccount.service.core;

import jakarta.servlet.http.HttpServletRequest;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.dto.request.EmailCodeLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.EmailCodeSendRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PasswordLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PhoneCodeLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PhoneCodeSendRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.RegisterRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.response.LoginResponse;

public interface AuthService {

    /**
     * 注册。
     *
     * <p>参数为用户名、密码、昵称与图形验证码：密码用 BCrypt 加密后落库，
     * 昵称只放行中文、字母、数字、下划线、连字符（挡掉 &lt; &gt; ' " 等危险字符），
     * 注册必须先通过图形验证码（人机校验）。
     *
     * <p>注册成功会自动绑定默认身份（ROLE_USER），权限随身份在登录时一并写入 Redis，
     * 新用户登录后即有普通用户该有的权限；更高权限由管理员后续分配。
     *
     * <p>注册成功不签发令牌：用户需要自己调用登录接口进行登录。
     *
     * @param registerRequest 注册请求参数（用户名、密码、昵称、图形验证码）
     * @param request         当前请求，用于解析客户端真实 IP 做限流
     */
    Result<Void> register(RegisterRequest registerRequest, HttpServletRequest request);

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
     * 手机验证码登录。
     *
     * <p>登录时必须同时通过图形验证码（人机校验）和短信验证码两道校验，
     * 两个验证码都是一次性的，校验通过后立即失效。
     *
     * @param phoneCodeLoginRequest 登录请求参数（手机号、短信验证码、图形验证码）
     * @param request               当前请求，用于解析客户端真实 IP 做限流
     */
    Result<LoginResponse> phoneCodeLogin(PhoneCodeLoginRequest phoneCodeLoginRequest, HttpServletRequest request);

    /**
     * 邮箱验证码登录。
     *
     * <p>登录时必须同时通过图形验证码（人机校验）和邮件验证码两道校验，
     * 两个验证码都是一次性的，校验通过后立即失效。
     *
     * @param emailCodeLoginRequest 登录请求参数（邮箱、邮件验证码、图形验证码）
     * @param request               当前请求，用于解析客户端真实 IP 做限流
     */
    Result<LoginResponse> emailCodeLogin(EmailCodeLoginRequest emailCodeLoginRequest, HttpServletRequest request);

    /**
     * 发送短信验证码。
     *
     * <p>只负责生成并下发验证码，不做人机校验（图形验证码在登录时校验）。
     * 验证码有效期 5 分钟，同一手机号 1 分钟内只能发一次。
     *
     * @param phoneCodeSendRequest 发送请求参数（手机号）
     * @param request              当前请求，用于解析客户端真实 IP 做限流
     */
    Result<Void> sendPhoneCode(PhoneCodeSendRequest phoneCodeSendRequest, HttpServletRequest request);

    /**
     * 发送邮件验证码。
     *
     * <p>只负责生成并下发验证码，不做人机校验（图形验证码在登录时校验）。
     * 验证码有效期 5 分钟，同一邮箱 1 分钟内只能发一次。
     *
     * @param emailCodeSendRequest 发送请求参数（邮箱）
     * @param request              当前请求，用于解析客户端真实 IP 做限流
     */
    Result<Void> sendEmailCode(EmailCodeSendRequest emailCodeSendRequest, HttpServletRequest request);

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
