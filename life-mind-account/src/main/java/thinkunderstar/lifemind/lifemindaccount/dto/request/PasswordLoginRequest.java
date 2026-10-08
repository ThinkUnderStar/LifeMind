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
     * 1-记住我模式 0-不记住我模式
     */
    private int rememberMe;
}
