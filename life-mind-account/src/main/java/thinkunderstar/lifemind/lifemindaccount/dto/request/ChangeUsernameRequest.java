package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 修改账户名请求参数
 *
 * <p>只有一个字段：账户名是登录用的名字，改它不需要验证码 / 原密码这类归属证明
 * （调用方本来就带着有效令牌，改的也只能是自己），只做格式校验、唯一性校验与限流。
 *
 * <p>操作的是当前登录用户自己，用户身份取自网关注入的请求头，不需要前端传用户 ID。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChangeUsernameRequest {

    /**
     * 新账户名，规则同注册：4 ~ 16 位，字母开头，只允许字母、数字、下划线
     */
    private String username;
}
