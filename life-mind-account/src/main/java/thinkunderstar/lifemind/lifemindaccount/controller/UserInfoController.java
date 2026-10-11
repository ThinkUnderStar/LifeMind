package thinkunderstar.lifemind.lifemindaccount.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.service.core.UserInfoService;

@RestController
@RequestMapping("/user")
public class UserInfoController {

    private final UserInfoService userInfoService;

    public UserInfoController(UserInfoService userInfoService) {
        this.userInfoService = userInfoService;
    }

    /**
     * 获取指定用户的权限标识（内部接口）。
     *
     * <p>供网关在 Redis 中查不到用户权限时回查数据库使用，
     * 已在 {@code SpringSecurityConfig} 与 {@code HeaderAuthenticationFilter} 中放行，
     * 由网关携带 {@code LifeMind-User-Id} 请求头调用，只允许内网访问；
     * 网关侧对 {@code /life-mind/account/user/internal/permissions} 是 denyAll，外部调不进来。
     *
     * <p>返回的 data 是逗号分隔的权限标识串，与 {@code LifeMind-User-Scopes}
     * 请求头的格式一致；用户存在但无权限时 data 为空串。
     *
     * @param userId 用户 ID
     */
    @GetMapping("/internal/permissions")
    public Result<String> internalPermissions(@RequestParam("userId") Long userId) {
        return userInfoService.getPermissionsByUserId(userId);
    }
}
