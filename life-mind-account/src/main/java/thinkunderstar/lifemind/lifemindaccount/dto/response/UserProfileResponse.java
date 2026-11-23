package thinkunderstar.lifemind.lifemindaccount.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 当前登录用户的资料。
 *
 * <p>账号服务此前只有「改」的接口，前端拿不到自己现在叫什么、绑没绑手机，
 * 个人主页只能靠登录时记下的账户名硬撑。这个响应补上读取这一环。
 *
 * <p>只返回展示需要、且属于用户自己的字段：密码、令牌版本号、状态这些
 * 不参与展示，也不该出现在前端。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    /**
     * 登录名
     */
    private String username;

    /**
     * 昵称：纯展示资料，未设置时为空
     */
    private String nickname;

    /**
     * 邮箱，未绑定为 null
     */
    private String email;

    /**
     * 手机号，未绑定为 null
     */
    private String phone;

    /**
     * 头像地址（形如 {@code /minio/life-mind-public/users/1/avatar/xxx.png}），未设置为 null
     */
    private String avatar;

    /**
     * 上次修改账户名的时间，null 表示从未改过。
     *
     * <p>账户名一年只能改一次，前端据此判断还能不能改。
     */
    private LocalDateTime usernameChangeTime;
}
