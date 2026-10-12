package thinkunderstar.lifemind.lifemindaccount.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.dto.request.BindEmailRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.BindPhoneRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByEmailRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByOriginalRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByPhoneRequest;
import thinkunderstar.lifemind.lifemindaccount.service.core.UserInfoService;

@RestController
@RequestMapping("/user")
public class UserInfoController {

    private final UserInfoService userInfoService;

    public UserInfoController(UserInfoService userInfoService) {
        this.userInfoService = userInfoService;
    }

    /*
     * 本控制器下都是「当前登录用户改自己的资料」，共七个接口，都不接受前端传用户 ID：
     *
     * 一、绑定 / 换绑手机号、邮箱：
     *   POST /user/bind/phone          绑定手机号：账号还没有手机号时用
     *   POST /user/rebind/phone        换绑手机号：账号已有手机号时用
     *   POST /user/bind/email          绑定邮箱：账号还没有邮箱时用
     *   POST /user/rebind/email        换绑邮箱：账号已有邮箱时用
     *   验证码发到「目标」号码（换绑时是新号码），先调 /auth/code/send/phone 或
     *   /auth/code/send/email 获取；换绑不需要先解绑旧号码。
     *
     * 二、修改密码，三条路任选其一：
     *   POST /user/password/original   用原密码改
     *   POST /user/password/phone      用短信验证码改
     *   POST /user/password/email      用邮箱验证码改
     *   后两个接口的请求里没有号码字段：验证码发给账号「已绑定的」手机 / 邮箱，
     *   由服务端自己取，前端指定不了；未绑定会提示先走对应的绑定接口。
     *   新密码规则与注册一致，且不能与原密码相同。
     *
     * 共同点：都要携带 Token（身份由网关从 JWT 解析后注入请求头，改的永远是「自己」）；
     * 都不做人机校验（图形验证码），靠验证码 / 原密码证明归属，配合限流防爆破；
     * 成功后都不重新签发令牌（手机号、邮箱、密码都不参与 JWT 声明），
     * 也都不做令牌失效处理，其他设备上的登录态不受影响。
     */

    /**
     * 绑定手机号：账号还没绑过手机号时用，已绑定会提示改用 {@code /user/rebind/phone}。
     */
    @PostMapping("/bind/phone")
    public Result<Void> bindPhone(
            @RequestBody BindPhoneRequest bindPhoneRequest,
            HttpServletRequest request
    ) {
        return userInfoService.bindPhone(bindPhoneRequest, request);
    }

    /**
     * 换绑手机号：账号已绑过手机号时用，未绑定会提示先走 {@code /user/bind/phone}。
     */
    @PostMapping("/rebind/phone")
    public Result<Void> rebindPhone(
            @RequestBody BindPhoneRequest bindPhoneRequest,
            HttpServletRequest request
    ) {
        return userInfoService.rebindPhone(bindPhoneRequest, request);
    }

    /**
     * 绑定邮箱：账号还没绑过邮箱时用，已绑定会提示改用 {@code /user/rebind/email}。
     */
    @PostMapping("/bind/email")
    public Result<Void> bindEmail(
            @RequestBody BindEmailRequest bindEmailRequest,
            HttpServletRequest request
    ) {
        return userInfoService.bindEmail(bindEmailRequest, request);
    }

    /**
     * 换绑邮箱：账号已绑过邮箱时用，未绑定会提示先走 {@code /user/bind/email}。
     */
    @PostMapping("/rebind/email")
    public Result<Void> rebindEmail(
            @RequestBody BindEmailRequest bindEmailRequest,
            HttpServletRequest request
    ) {
        return userInfoService.rebindEmail(bindEmailRequest, request);
    }

    /**
     * 用原密码修改密码：调用方需要同时知道当前密码。
     */
    @PostMapping("/password/original")
    public Result<Void> changePasswordByOriginal(
            @RequestBody ChangePasswordByOriginalRequest changePasswordByOriginalRequest,
            HttpServletRequest request
    ) {
        return userInfoService.changePasswordByOriginal(changePasswordByOriginalRequest, request);
    }

    /**
     * 用手机短信验证码修改密码：验证码发到账号已绑定的手机号，
     * 未绑定手机号会提示先走 {@code /user/bind/phone}。
     */
    @PostMapping("/password/phone")
    public Result<Void> changePasswordByPhone(
            @RequestBody ChangePasswordByPhoneRequest changePasswordByPhoneRequest,
            HttpServletRequest request
    ) {
        return userInfoService.changePasswordByPhone(changePasswordByPhoneRequest, request);
    }

    /**
     * 用邮箱验证码修改密码：验证码发到账号已绑定的邮箱，
     * 未绑定邮箱会提示先走 {@code /user/bind/email}。
     */
    @PostMapping("/password/email")
    public Result<Void> changePasswordByEmail(
            @RequestBody ChangePasswordByEmailRequest changePasswordByEmailRequest,
            HttpServletRequest request
    ) {
        return userInfoService.changePasswordByEmail(changePasswordByEmailRequest, request);
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
