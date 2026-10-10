package thinkunderstar.lifemind.lifemindaccount.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import thinkunderstar.lifemind.lifemindaccount.security.LifeMindUserDetails;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 下游服务的身份恢复过滤器。
 *
 * <p>认证已经在网关完成，本过滤器不重复验证 JWT，只做一件事：
 * 从网关注入的 Header 中读取用户身份，构建已认证的 Authentication 对象，
 * 写入 SecurityContext，让后续的授权过滤器认为"该请求已登录"。
 *
 * <p>依赖的请求头（由网关注入）：
 * <ul>
 *     <li>LifeMind-User-Id      用户 ID</li>
 *     <li>LifeMind-User-Roles   角色，逗号分隔</li>
 *     <li>LifeMind-User-Scopes  权限，逗号分隔</li>
 * </ul>
 *
 * <p>前置条件：本服务只在内网部署，公网访问必须经过网关，
 * 否则攻击者可伪造 Header 绕过认证。
 */
@Component
public class HeaderAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        //放行登录接口与图形验证码接口（这些接口在下游本来就不需要用户身份）
        if (
                request.getRequestURI().equals("/life-mind/account/auth/login/password") ||
                        request.getRequestURI().equals("/life-mind/account/auth/internal/permissions") ||
                        request.getRequestURI().equals("/life-mind/account/captcha/get")
        ) {
            filterChain.doFilter(request, response);
            return;
        }

        //获取请求头中上游服务传下来的信息
        String userIdHeader = request.getHeader("LifeMind-User-Id");
        if (userIdHeader == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,"无必要请求头");
            return;
        }
        Long userId = null;
        try {
            userId = Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,"请求意外参数");
            return;
        }
        String roles = request.getHeader("LifeMind-User-Roles");
        if (roles == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,"无必要请求头");
            return;
        }
        List<String> roleList =  Arrays.asList(roles.split(","));
        String scopes = request.getHeader("LifeMind-User-Scopes");
        if (scopes == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,"无必要请求头");
            return;
        }
        List<String> scopeList =  Arrays.asList(scopes.split(","));

        //构建UserDetails
        LifeMindUserDetails lifeMindUserDetails = new LifeMindUserDetails();
        lifeMindUserDetails.setUserId(userId);
        lifeMindUserDetails.setUsername("username");
        lifeMindUserDetails.setPassword("password");
        Collection<GrantedAuthority> grantedAuthorities = new ArrayList<>();
        roleList.forEach(role -> {
            grantedAuthorities.add(new SimpleGrantedAuthority(role));
        });
        scopeList.forEach(scope -> {
            grantedAuthorities.add(new SimpleGrantedAuthority(scope));
        });
        lifeMindUserDetails.setAuthorities(grantedAuthorities);

        //创建Authentication对象
        UsernamePasswordAuthenticationToken authRequest
                = new UsernamePasswordAuthenticationToken(
                        lifeMindUserDetails,
                null,
                lifeMindUserDetails.getAuthorities()
        );

        //将Authentication对象注入到context中，实现登录
        SecurityContextHolder.getContext().setAuthentication(authRequest);
        filterChain.doFilter(request, response);
    }
}
