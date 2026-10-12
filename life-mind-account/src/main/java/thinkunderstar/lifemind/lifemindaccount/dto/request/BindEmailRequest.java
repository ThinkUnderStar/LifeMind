package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 绑定 / 换绑邮箱请求参数
 *
 * <p>绑定与换绑的入参完全一致，所以两者复用同一个 DTO：区别只在调哪个接口，
 * 绑定要求账号还没有邮箱，换绑要求账号已经有邮箱。
 *
 * <p>不做人机校验，因此没有图形验证码的 tempKey / captchaCode 字段；
 * 邮箱归属靠邮件验证码证明，验证码由 {@code /auth/code/send/email} 下发（换绑时发到新邮箱）。
 * 操作的是当前登录用户自己，用户身份取自网关注入的请求头，不需要前端传用户 ID。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BindEmailRequest {

    /**
     * 要绑定的邮箱地址
     */
    private String email;

    /**
     * 邮件验证码（6 位数字），由 {@code /auth/code/send/email} 下发
     */
    private String code;
}
