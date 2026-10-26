package thinkunderstar.lifemind.lifemindgateway.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import thinkunderstar.lifemind.lifemindgateway.filter.JWTAuthFilter;

@Configuration
//webflux框架
@EnableWebFluxSecurity
public class SpringSecurityConfig {
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http, JWTAuthFilter jWTAuthFilter) throws Exception {
        http.authorizeExchange(exchange -> {
            exchange
                    //注册接口：注册前没有 Token，必须放行
                    .pathMatchers("/life-mind/account/auth/register").permitAll()
                    //登录接口（密码 / 手机验证码 / 邮箱验证码）：登录前没有 Token，必须放行
                    .pathMatchers("/life-mind/account/auth/login/**").permitAll()
                    //验证码发送接口：同样在登录前调用
                    .pathMatchers("/life-mind/account/auth/code/send/**").permitAll()
                    //获取人机验证图
                    .pathMatchers("/life-mind/account/captcha/**").permitAll()
                    //访问minio的公开桶
                    .pathMatchers("/minio/life-mind-public/**").permitAll()
                    //查询权限、查询令牌版本属于内部接口不接受调用
                    .pathMatchers("/life-mind/account/user/internal/permissions").denyAll()
                    .pathMatchers("/life-mind/account/user/internal/token-version").denyAll()
                    .pathMatchers("/**").authenticated();
        });

        /**
         * 跨域（CORS）不在网关处理，统一交给前置的 Nginx 反向代理：
         * 预检 OPTIONS 请求由 Nginx 直接返回 204 并补上响应头，不会转发到这里；
         * 因此网关既不注册 CorsWebFilter，也不需要在授权规则里单独放行 OPTIONS。
         * 若后续不走 Nginx 而直连网关，浏览器跨域请求会因缺少响应头被拦截，需自行加回 CORS 配置。
         */

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

        /**
         * 关闭CSRF防护
         */
        http.csrf(csrf -> csrf.disable());

        return http.build();
    }
}
