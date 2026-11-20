package thinkunderstar.lifemind.lifemindschedule.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import thinkunderstar.lifemind.lifemindschedule.filter.HeaderAuthenticationFilter;

/**
 * 安全配置。
 *
 * <p>本服务不做登录，也不发令牌：认证在网关完成，这里只负责
 * 「把网关注入的请求头还原成已登录身份」并把所有接口都要求为已认证。
 *
 * <p>与 account 的区别：没有登录/注册/验证码这类公开接口，所以没有 permitAll 白名单；
 * 只放行 {@code /error}，防止错误转发被二次拦成 401。
 */
@Configuration
@EnableWebSecurity
public class SpringSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            HeaderAuthenticationFilter headerAuthenticationFilter
    ) throws Exception {
        http.addFilterBefore(headerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        // 关闭 Spring Security 自带的表单登录、退出登录、HTTP Basic
        http.formLogin(form -> form.disable());
        http.logout(logout -> logout.disable());
        http.httpBasic(basic -> basic.disable());

        http.authorizeHttpRequests(auth -> auth
                // 容器内部的错误转发，不放行会让真实异常被 401 掩盖
                .requestMatchers("/error").permitAll()
                // 其余接口全部要求已认证：身份由上面的过滤器从头里还原
                .anyRequest().authenticated()
        );

        // 无状态：本服务不保存会话，每个请求都靠网关注入的头
        http.sessionManagement(session -> session.disable());

        // 纯 JSON 接口，没有浏览器表单，关掉 CSRF
        http.csrf(csrf -> csrf.disable());

        return http.build();
    }
}
