package thinkunderstar.lifemind.lifemindaccount.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import thinkunderstar.lifemind.lifemindaccount.filter.HeaderAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SpringSecurityConfig {

    /**
     * 密码编码器：BCrypt 算法，与库中 jBCrypt 生成的 {@code $2a$} 密文互相兼容，
     * 校验和加密统一走它。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, HeaderAuthenticationFilter headerAuthenticationFilter) throws Exception {
        //添加下游服务的身份恢复过滤器
        http.addFilterBefore(headerAuthenticationFilter,UsernamePasswordAuthenticationFilter.class);

        //关闭自带的退出登录接口
        http.logout(logout -> logout.disable());

        //关闭自带的表单登录
        http.formLogin(form -> form.disable());

        //所有请求过Security的过滤链
        http.authorizeHttpRequests(
                auth -> auth
                        //注册接口：注册前没有身份，必须放行
                        .requestMatchers("/auth/register").permitAll()
                        //登录接口：登录前没有身份，必须放行
                        .requestMatchers("/auth/login/password").permitAll()
                        .requestMatchers("/auth/login/phone").permitAll()
                        .requestMatchers("/auth/login/email").permitAll()
                        //验证码发送接口：同样在登录前调用
                        .requestMatchers("/auth/code/send/phone").permitAll()
                        .requestMatchers("/auth/code/send/email").permitAll()
                        //图形验证码：登录前就要拿到图片，不然上面的登录接口没法用
                        .requestMatchers("/captcha/get").permitAll()
                        //内部接口，由网关回查权限时调用
                        .requestMatchers("/auth/internal/permissions").permitAll()
                        .anyRequest().authenticated()
        );

        //关闭session认证模式
        http.sessionManagement(sessionManagement ->sessionManagement.disable());

        return http.build();
    }
}
