package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送短信验证码请求参数
 *
 * <p>这里只需要手机号：图形验证码与短信验证码都在登录时校验，
 * 发送接口本身不要求携带。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PhoneCodeSendRequest {

    /**
     * 手机号
     */
    private String phone;
}
