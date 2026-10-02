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
}
