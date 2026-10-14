package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 修改昵称请求参数
 *
 * <p>只有一个字段：昵称是纯展示用的资料，改它不需要任何凭证（不像手机号 / 邮箱 / 密码），
 * 所以没有验证码、原密码之类的字段，也不做人机校验。
 *
 * <p>操作的是当前登录用户自己，用户身份取自网关注入的请求头，不需要前端传用户 ID。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChangeNicknameRequest {

    /**
     * 新昵称，长度与字符集要求同注册：1 ~ 16 位，只允许中文、字母、数字、下划线、连字符
     */
    private String nickname;
}
