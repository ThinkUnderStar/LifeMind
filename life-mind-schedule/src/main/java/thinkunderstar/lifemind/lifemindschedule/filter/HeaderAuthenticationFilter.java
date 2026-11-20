package thinkunderstar.lifemind.lifemindschedule.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.common.ResultCode;
import thinkunderstar.lifemind.lifemindschedule.security.LifeMindUserDetails;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 下游服务的身份恢复过滤器。
 *
 * <p>认证已经在网关完成，本过滤器不重复验证 JWT，只做两件事：
 * 从网关注入的 Header 里读用户身份，构建已认证的 Authentication 写入 SecurityContext；
 * 顺便记录这个请求是谁发起的（前端还是 agent），落到数据的 {@code source} 字段上。
 *
 * <p>依赖的请求头（由网关注入）：
 * <ul>
 *     <li>LifeMind-User-Id      用户 ID（必需）</li>
 *     <li>LifeMind-User-Roles   角色，逗号分隔（必需）</li>
 *     <li>LifeMind-User-Scopes  权限，逗号分隔（必需）</li>
 *     <li>LifeMind-Client       调用方标识（可选）：web-前端 agent-智能体，缺省按 web</li>
 * </ul>
 *
 * <p>本服务没有「公开接口」：日程、待办、备忘录、统计全都是某个用户自己的数据，
 * 所以不像 account 那样维护登录/验证码白名单，只放行 Spring 的错误转发路径
 * <code>/error</code>——否则异常响应会被这里二次拦成 401，真实错误反而看不到。
 *
 * <p>错误响应在这里直接写成与业务接口一致的 {@link Result} JSON，
 * 而不是用 {@code sendError}：后者会走容器的错误页，返回的是
 * {@code {timestamp,status,error,path}}，调用方得为「401」写第二套解析逻辑。
 *
 * <p>前置条件：本服务只在内网部署，公网访问必须经过网关，
 * 否则攻击者可以直接伪造 Header 绕过认证。
 */
@Component
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Spring Boot 的错误转发路径：容器内部转发，不带业务请求头
     */
    private static final String ERROR_PATH = "/error";

    /**
     * 调用方标识头：网关和 agent 都可以带，本服务只用来记录数据来源
     */
    private static final String CLIENT_HEADER = "LifeMind-Client";

    /**
     * 数据来源取值：缺省按用户手写
     */
    private static final String SOURCE_WEB = "web";
    private static final String SOURCE_AGENT = "agent";

    private final ObjectMapper objectMapper;

    public HeaderAuthenticationFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String uri = request.getRequestURI();
        // 本服务有 context-path（/life-mind/schedule），getRequestURI() 是带前缀的，
        // 直接和 "/error" 比永远不相等——那样异常转发会被当成普通请求拦成 401，
        // 真实错误全被掩盖。所以先剥掉 context-path 再比
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
        if (ERROR_PATH.equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String userIdHeader = request.getHeader("LifeMind-User-Id");
        if (userIdHeader == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少身份信息，请通过网关访问");
            return;
        }

        Long userId;
        try {
            userId = Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "用户身份不合法");
            return;
        }

        String roles = request.getHeader("LifeMind-User-Roles");
        String scopes = request.getHeader("LifeMind-User-Scopes");
        if (roles == null || scopes == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少身份信息，请通过网关访问");
            return;
        }

        List<String> roleList = Arrays.asList(roles.split(","));
        List<String> scopeList = Arrays.asList(scopes.split(","));

        LifeMindUserDetails userDetails = new LifeMindUserDetails();
        userDetails.setUserId(userId);
        userDetails.setUsername("username");
        userDetails.setPassword("password");
        userDetails.setClient(resolveClient(request));

        Collection<GrantedAuthority> grantedAuthorities = new ArrayList<>();
        roleList.forEach(role -> grantedAuthorities.add(new SimpleGrantedAuthority(role)));
        scopeList.forEach(scope -> grantedAuthorities.add(new SimpleGrantedAuthority(scope)));
        userDetails.setAuthorities(grantedAuthorities);

        UsernamePasswordAuthenticationToken authRequest
                = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        SecurityContextHolder.getContext().setAuthentication(authRequest);
        filterChain.doFilter(request, response);
    }

    /**
     * 识别调用方：只有明确写了 agent 才算 agent，其余一律按 web。
     *
     * <p>取白名单而不是原样透传：这个值会落库，放任调用方自定义等于给自己留一个
     * 「谁都能写任意来源」的口子，后续按来源做权限或统计就不可信了。
     */
    private String resolveClient(HttpServletRequest request) {
        String client = request.getHeader(CLIENT_HEADER);
        if (client == null) {
            return SOURCE_WEB;
        }
        return SOURCE_AGENT.equalsIgnoreCase(client.trim()) ? SOURCE_AGENT : SOURCE_WEB;
    }

    /**
     * 直接写 JSON 错误，避免容器错误页的响应结构与业务接口不一致。
     */
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(status, message)));
    }
}
