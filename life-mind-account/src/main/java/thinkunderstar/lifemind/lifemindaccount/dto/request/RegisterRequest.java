package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 注册请求参数
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequest {

    /**
     * 登录名
     */
    private String username;

    /**
     * 明文密码，落库前会用 BCrypt 加密
     */
    private String password;

    /**
     * 昵称：不允许为空，只放行中文、字母、数字、下划线、连字符，
     * 天然挡掉 &lt; &gt; ' " 等危险字符（防脚本注入）
     */
    private String nickname;

    /**
     * 图形验证码的临时 key，由前端调用 /captcha/get 时生成并传入
     */
    private String tempKey;

    /**
     * 用户填写的图形验证码（不区分大小写）
     */
    private String captchaCode;
}
