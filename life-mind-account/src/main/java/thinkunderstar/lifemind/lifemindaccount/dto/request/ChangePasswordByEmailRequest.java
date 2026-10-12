package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用邮箱验证码修改密码的请求参数。
 *
 * <p>请求里只有验证码，没有邮箱地址：地址由服务端取当前账号「已经绑定的那个」，
 * 客户端指定不了。验证码由 {@code /auth/code/send/email} 下发到该邮箱，
 * 先调发送接口再调本接口。
 *
 * <p>账号尚未绑定邮箱时会提示先走 {@code /user/bind/email}。
 * 不做人机校验（图形验证码），调用方已登录，归属靠邮箱验证码证明。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChangePasswordByEmailRequest {

    /**
     * 邮箱验证码（6 位数字），由 {@code /auth/code/send/email} 下发到账号已绑定的邮箱
     */
    private String code;

    /**
     * 新密码（明文），长度与字符集要求同注册
     */
    private String newPassword;

    /**
     * 确认新密码，仅用于校验两次输入是否一致，不落库
     */
    private String confirmPassword;
}
