package thinkunderstar.lifemind.lifemindaccount.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String jwtToken;
    /**
     * 1-记住我模式 0-不记住我模式
     */
    private int rememberMe;
}
