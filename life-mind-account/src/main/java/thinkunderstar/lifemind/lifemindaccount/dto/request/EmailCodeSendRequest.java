package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送邮件验证码请求参数
 *
 * <p>这里只需要邮箱：图形验证码与邮件验证码都在登录时校验，
 * 发送接口本身不要求携带。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmailCodeSendRequest {

    /**
     * 邮箱地址
     */
    private String email;
}
