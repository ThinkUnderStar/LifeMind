package thinkunderstar.lifemind.lifemindaccount.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PasswordLoginRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.response.LoginResponse;

@RestController
@RequestMapping("/auth")
public class AuthController {
    @PostMapping("/login/password")
    public Result<LoginResponse> login(
            @RequestBody PasswordLoginRequest passwordLoginRequest
    ){

    }
}
