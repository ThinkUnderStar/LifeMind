package thinkunderstar.lifemind.lifemindgateway.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import thinkunderstar.lifemind.lifemindgateway.filter.JWTAuthFilter;

import java.util.List;

@Configuration
//webflux框架
@EnableWebFluxSecurity
public class SpringSecurityConfig {
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http, JWTAuthFilter jWTAuthFilter) throws Exception {
        http.authorizeExchange(exchange -> {
            exchange
                    //CORS 预检请求不携带 Token，必须放行
                    .pathMatchers(HttpMethod.OPTIONS).permitAll()
                    .pathMatchers("/life-mind/account/auth/login").permitAll()
                    .pathMatchers("/**").authenticated();
        });

        /**
         * 开启CORS跨域支持，使用下面 corsConfigurationSource 中的规则
         * CORS 过滤器位于 AUTHENTICATION 之前，预检请求会在这里直接返回，不会走到 JWT 校验
         */
        http.cors(
                httpSecurityCorsConfigurer ->
                        httpSecurityCorsConfigurer
                                .configurationSource(corsConfigurationSource())
        );

        /**
         * 关闭框架自带的登录表单
         */
        http.formLogin(
                httpSecurityFormLoginConfigurer ->
                httpSecurityFormLoginConfigurer
                        .disable()
        );

        /**
         * 关闭默认的退出登录接口
         */
        http.logout(
                httpSecurityLogoutConfigurer ->
                        httpSecurityLogoutConfigurer.disable()
        );

        /**
         * 添加JWT验证过滤器
         */
        http.addFilterBefore(jWTAuthFilter, SecurityWebFiltersOrder.AUTHENTICATION);

        /**
         * 关闭session
         */
        http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        return http.build();
    }

    /**
     * CORS 跨域规则
     * 注意：allowedOriginPatterns 用通配符时不能再用 allowedOrigins("*")，
     * 否则和 allowCredentials(true) 同时配置会被 Spring 直接拒绝
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        //允许的来源，开发阶段放开全部；上线前建议改成具体域名，例如 https://xxx.com
        config.setAllowedOriginPatterns(List.of("*"));

        //允许的请求方法
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));

        //允许携带的请求头
        config.setAllowedHeaders(List.of("*"));

        //允许前端读取的响应头
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));

        //是否允许携带 Cookie 等凭证
        config.setAllowCredentials(true);

        //预检请求结果的缓存时间（秒），减少 OPTIONS 请求次数
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        //网关下所有路由都生效
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
