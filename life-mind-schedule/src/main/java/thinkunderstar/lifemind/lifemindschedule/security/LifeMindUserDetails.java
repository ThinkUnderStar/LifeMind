package thinkunderstar.lifemind.lifemindschedule.security;

import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * 下游服务的用户身份载体。
 *
 * <p>字段与 account / message 服务一致：认证在网关完成，这里只是把
 * {@code LifeMind-User-*} 请求头还原成一个「已登录」的用户对象，
 * 业务层要用户 ID 时从 SecurityContext 取，不接受前端自报。
 */
@Data
public class LifeMindUserDetails implements UserDetails {

    /**
     * 用户账户名：下游服务拿不到也不需要，占位满足接口
     */
    private String username;

    /**
     * 用户密码：下游不校验密码，占位满足接口
     */
    private String password;

    /**
     * 用户ID：网关注入，业务层的唯一归属依据
     */
    private Long userId;

    /**
     * 调用方标识：web-前端手写 agent-智能体代操作。
     *
     * <p>只用于给新建数据打来源标记，不参与任何鉴权判断——
     * 它是用来回答「这条日程是谁建的」，不是用来决定「谁能改」。
     */
    private String client;

    /**
     * 角色与权限
     */
    private Collection<? extends GrantedAuthority> authorities;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities;
    }

    @Override
    public String getPassword() {
        return this.password;
    }

    @Override
    public String getUsername() {
        return this.username;
    }

    /**
     * 以下四个状态位全部返回 true：
     *
     * <p>账号是否过期 / 锁定 / 凭证过期 / 是否启用，这些判断在网关校验 JWT 时已经做过。
     * 这里返回 false 会被 Spring Security 当成「账号不可用」直接拒绝所有请求，
     * 表现为「网关注入的头都对，但每个接口都 403」——account 服务里返回 false 是个隐患，
     * 本服务不复制这个写法。
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
