package thinkunderstar.lifemind.lifemindaccount.service.core;

import jakarta.servlet.http.HttpServletRequest;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.dto.request.BindEmailRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.BindPhoneRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByEmailRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByOriginalRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.request.ChangePasswordByPhoneRequest;

import java.util.Collection;

/**
 * 用户信息维度的服务。
 *
 * <p>承载三类职责：
 * <ul>
 *     <li>用户权限标识的查询与缓存：登录时把权限写入 Redis 供网关读取，
 *     网关缓存未命中时再回查数据库并回填。这两条路径必须共用同一份 Redis key 与过期时间，
 *     所以统一收敛到本服务，避免 key 拼接逻辑在多处各存一份而静默失配。</li>
 *     <li>用户资料的修改：绑定 / 换绑手机号与邮箱。归属校验靠短信 / 邮件验证码，
 *     不做人机校验（图形验证码）；绑定与换绑分成各自的入口（已绑定走绑定接口会提示换绑），
 *     但四个入口共用同一段实现。</li>
 *     <li>密码的修改：原密码 / 手机验证码 / 邮箱验证码三条路，同样分成各自的入口，
 *     落库那一段共用同一段实现。</li>
 * </ul>
 */
public interface UserInfoService {

    /*
     * 绑定 / 换绑手机号、邮箱四个入口的共同约定：
     *   1. 都只作用于「当前登录用户」：身份取自网关注入的请求头，不接收前端传来的用户 ID，
     *      否则登录用户可以改别人的资料；
     *   2. 不做人机校验（图形验证码），归属靠验证码证明——手机号走 /auth/code/send/phone
     *      的短信验证码，邮箱走 /auth/code/send/email 的邮件验证码，换绑时验证码发到「新」号码；
     *   3. 先验码、再查重：避免登录用户拿别人的号码试探，从「已被其他账号绑定」这条提示里
     *      反推出该号码是否已注册；
     *   4. 成功后不重新签发令牌：手机号与邮箱都不参与 JWT 声明，账号权限也不随绑定变化。
     */

    /**
     * 绑定手机号：账号还没绑过手机号时才能用，已绑定会提示改用换绑接口。
     *
     * @param bindPhoneRequest 请求参数（手机号、短信验证码）
     * @param request          当前请求，用于解析客户端真实 IP 做限流
     */
    Result<Void> bindPhone(BindPhoneRequest bindPhoneRequest, HttpServletRequest request);

    /**
     * 换绑手机号：账号已绑过手机号时才能用，未绑定会提示先走绑定接口；
     * 直接改成新号码，不需要先解绑旧号码，验证码发到新号码；
     * 新号码与当前号码相同时报错，不会当成一次成功的空操作。
     */
    Result<Void> rebindPhone(BindPhoneRequest bindPhoneRequest, HttpServletRequest request);

    /**
     * 绑定邮箱：账号还没绑过邮箱时才能用，已绑定会提示改用换绑接口。
     */
    Result<Void> bindEmail(BindEmailRequest bindEmailRequest, HttpServletRequest request);

    /**
     * 换绑邮箱：账号已绑过邮箱时才能用，未绑定会提示先走绑定接口，验证码发到新邮箱；
     * 新邮箱与当前邮箱相同时报错。
     */
    Result<Void> rebindEmail(BindEmailRequest bindEmailRequest, HttpServletRequest request);

    /*
     * 修改密码三个入口的共同约定：
     *   1. 都只作用于「当前登录用户」：身份取自网关注入的请求头，不接收前端传来的用户 ID，
     *      否则登录用户可以改别人的密码；
     *   2. 只改密码，不动令牌：密码不参与 JWT 声明，签发出去的令牌也不做失效处理，
     *      所以改密不会把其他设备上的登录态踢掉（见实现的注释）；
     *   3. 手机 / 邮箱两条路由服务端自取「账号已绑的号码」去验码，请求里只有验证码，
     *      客户端指定不了号码；未绑定会提示先走对应的绑定接口；
     *   4. 新密码统一按注册时的规则校验（长度 + 字符集），保证改完还能正常登录，
     *      并且要求与原密码不同——这条判断刻意排在「证明本人」之后，否则会变成猜原密码的判定器。
     */

    /**
     * 用原密码修改密码：调用方需要同时知道当前密码。
     *
     * @param changePasswordByOriginalRequest 请求参数（原密码、新密码、确认新密码）
     * @param request                         当前请求，用于解析客户端真实 IP 做限流
     */
    Result<Void> changePasswordByOriginal(ChangePasswordByOriginalRequest changePasswordByOriginalRequest,
                                          HttpServletRequest request);

    /**
     * 用手机短信验证码修改密码：验证码发到账号已绑定的手机号，
     * 未绑定手机号会提示先走 {@code /user/bind/phone}。
     *
     * @param changePasswordByPhoneRequest 请求参数（短信验证码、新密码、确认新密码）
     * @param request                      当前请求，用于解析客户端真实 IP 做限流
     */
    Result<Void> changePasswordByPhone(ChangePasswordByPhoneRequest changePasswordByPhoneRequest,
                                       HttpServletRequest request);

    /**
     * 用邮箱验证码修改密码：验证码发到账号已绑定的邮箱，
     * 未绑定邮箱会提示先走 {@code /user/bind/email}。
     *
     * @param changePasswordByEmailRequest 请求参数（邮箱验证码、新密码、确认新密码）
     * @param request                      当前请求，用于解析客户端真实 IP 做限流
     */
    Result<Void> changePasswordByEmail(ChangePasswordByEmailRequest changePasswordByEmailRequest,
                                       HttpServletRequest request);

    /**
     * 根据用户 ID 查询该用户拥有的全部权限标识（已去重）。
     *
     * <p>内部接口，供网关在 Redis 中查不到用户权限时回查数据库使用。
     * 返回的是逗号分隔的权限标识串，与登录时写入 Redis 的格式一致；
     * 用户存在但没有任何权限时返回空串（而不是 null），
     * 这样网关可以区分「查过了，确实没权限」和「还没查过」。
     *
     * @param userId 用户 ID
     * @return 权限标识串，多个用英文逗号分隔，无权限时为空串
     */
    Result<String> getPermissionsByUserId(Long userId);

    /**
     * 根据身份 ID 集合加载权限标识串。
     *
     * <p>链路：identity_permission 关联 permission 查权限。
     * 权限按权限标识去重，防止同一权限被多个身份重复授予时返回重复项；
     * 无身份或身份下无权限时返回空串。
     *
     * <p>收身份 ID 集合而不是用户 ID：登录路径手里已经有身份 ID（签令牌要用），
     * 直接传进来可以少查一次 user_identity。
     *
     * @param identityIds 身份 ID 集合，可为空
     * @return 权限标识串，多个用英文逗号分隔，无权限时为空串
     */
    String loadScopesByIdentityIds(Collection<Long> identityIds);

    /**
     * 按统一的 key 与过期时间把权限写入 Redis 缓存。空串同样会被缓存，
     * 表示「该用户确实没有权限」，避免网关每次都回查数据库。
     *
     * @param userId 用户 ID
     * @param scopes 权限标识串，多个用英文逗号分隔
     */
    void cacheScopes(Long userId, String scopes);
}
