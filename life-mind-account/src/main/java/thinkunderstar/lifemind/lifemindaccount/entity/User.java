package thinkunderstar.lifemind.lifemindaccount.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表
 */
@Data
@TableName("user")
public class User {

    /**
     * 用户ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 登录名
     */
    private String username;

    /**
     * 上次修改账户名的时间，null 表示从未改过。
     *
     * <p>账户名一年只能改一次，改的时候把当前时间写进来，下次改之前先比对这个时间。
     * 注册时留空（注册不算一次「修改」），所以新账号随时都能改第一次。
     */
    private LocalDateTime usernameChangeTime;

    /**
     * 密码（BCrypt 加密后的密文）
     */
    private String password;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 头像地址
     */
    private String avatar;

    /**
     * 状态：0-禁用 1-正常
     */
    private Integer status;

    /**
     * 令牌版本号：登录时写进 JWT 负载，修改密码后 +1。
     *
     * <p>网关会拿 JWT 里的版本号与当前版本号比对，对不上就拒绝该令牌，
     * 所以改密后此前签发的所有令牌都会立即失效（旧令牌里的版本号偏小）。
     * 从 0 开始，只增不减。
     */
    private Integer tokenVersion;

    /**
     * 逻辑删除：0-未删除 1-已删除
     */
    @TableLogic
    private Integer deleted;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
