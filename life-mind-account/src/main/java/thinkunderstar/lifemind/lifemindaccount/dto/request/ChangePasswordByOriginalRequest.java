package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用原密码修改密码的请求参数。
 *
 * <p>改密有三条路（原密码 / 手机验证码 / 邮箱验证码），入参只有「怎么证明是本人」这一处不同，
 * 所以三条路各有一个 DTO，分别对应一个接口；新密码与确认新密码三个 DTO 完全一致。
 *
 * <p>不做人机校验（图形验证码），因为调用方已登录：归属由原密码本身证明，
 * 防爆破交给接口层的限流。操作的是当前登录用户自己，身份取自网关注入的请求头，
 * 不需要前端传用户 ID。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChangePasswordByOriginalRequest {

    /**
     * 当前正在使用的密码（明文），用于证明是本人
     */
    private String originalPassword;

    /**
     * 新密码（明文），长度与字符集要求同注册
     */
    private String newPassword;

    /**
     * 确认新密码，仅用于校验两次输入是否一致，不落库
     */
    private String confirmPassword;
}
