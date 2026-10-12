package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用手机短信验证码修改密码的请求参数。
 *
 * <p>请求里只有验证码，没有手机号：号码由服务端取当前账号「已经绑定的那个」，
 * 客户端指定不了，也就不存在「验了 A 号码却改了 B 账号」的错配。
 * 验证码由 {@code /auth/code/send/phone} 下发到该号码，先调发送接口再调本接口。
 *
 * <p>账号尚未绑定手机号时会提示先走 {@code /user/bind/phone}。
 * 不做人机校验（图形验证码），调用方已登录，归属靠短信验证码证明。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChangePasswordByPhoneRequest {

    /**
     * 短信验证码（6 位数字），由 {@code /auth/code/send/phone} 下发到账号已绑定的手机号
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
