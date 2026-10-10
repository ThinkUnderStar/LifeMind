package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PasswordLoginRequest {
    private String username;
    private String password;

    /**
     * 图形验证码的临时 key，由前端调用 /captcha/get 时生成并传入
     */
    private String tempKey;

    /**
     * 用户填写的图形验证码（不区分大小写）
     */
    private String captchaCode;

    /**
     * 1-记住我模式 0-不记住我模式
     */
    private int rememberMe;
}
