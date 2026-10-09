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
                        .requestMatchers("/auth/login").permitAll()
                        .anyRequest().authenticated()
        );

        //关闭session认证模式
        http.sessionManagement(sessionManagement ->sessionManagement.disable());

        return http.build();
    }
}
