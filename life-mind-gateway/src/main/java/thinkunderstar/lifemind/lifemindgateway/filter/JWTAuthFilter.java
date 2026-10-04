package thinkunderstar.lifemind.lifemindgateway.filter;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import thinkunderstar.lifemind.lifemindgateway.util.ResponseUtils;

import java.util.ArrayList;
import java.util.List;

@RefreshScope
@Component
public class JWTAuthFilter implements WebFilter {
    private static final Logger log = LoggerFactory.getLogger(JWTAuthFilter.class);
    //用于把 Redis 里存的权限 JSON 解析成列表
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final ReactiveRedisTemplate<Object, Object> reactiveRedisTemplate;
    @Value("${jwt.key}")
    private String jwtKey;

    public JWTAuthFilter(ReactiveRedisTemplate<Object, Object> reactiveRedisTemplate) {
        this.reactiveRedisTemplate = reactiveRedisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain filterChain) {
        if (exchange.getRequest().getURI().getPath().startsWith("/life-mind/account/auth/login")) {
            return filterChain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseUtils.writeError(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "TOKEN_MISSING",
                    "请求未携带认证 Token，或格式不是 Bearer <token>"
            );
        }

        authorization = authorization.substring(7);

        DecodedJWT jwt;
        try {
            jwt = JWT.require(Algorithm.HMAC256(jwtKey)).build().verify(authorization);
        } catch (TokenExpiredException e) {
            return ResponseUtils.writeError(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "TOKEN_EXPIRED",
                    "Token 已过期，请重新登录"
            );
        } catch (JWTVerificationException e) {
            return ResponseUtils.writeError(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "TOKEN_INVALID",
                    "Token 无效"
            );
        }

        //获取用户信息
        String userId = jwt.getClaim("LifeMind-User-Id").asString();

        //向SecurityContext注入值，跳过认证过滤器（只用 JWT 里的信息，不需要查 Redis）
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        String roles = jwt.getClaim("LifeMind-User-Roles").asString();
        if (roles != null && !roles.isBlank()) {
            for (String role : roles.split(",")) {
                if (!role.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim()));
                }
            }
        }
        SecurityContext securityContext = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userId, null, authorities)
        );

        //用户权限列表
        List<String> scopes = new ArrayList<>();

        return reactiveRedisTemplate.opsForValue().get("life-mind:" + userId + ":scopes")
                .defaultIfEmpty("none")
                //Redis 连接异常：直接写回错误响应，后面的 flatMap 不会再执行
                .onErrorResume(
                        RedisConnectionFailureException.class,
                        e -> {
                            log.error("连接 Redis 查询用户权限失败", e);
                            return ResponseUtils.writeError(
                                    exchange,
                                    HttpStatus.INTERNAL_SERVER_ERROR,
                                    "SERVER_ERROR",
                                    "服务端出现异常"
                            );
                        }
                )
                .flatMap(json -> {
                    if (json.toString().equals("none")) {
                        //调用服务查询mysql中的权限
                    }

                    //把 Redis 里存的权限 JSON 解析成列表，例如 ["user:read","user:write"]
                    try {
                        scopes.addAll(OBJECT_MAPPER.readValue(json.toString(), new TypeReference<List<String>>() {
                        }));
                    } catch (Exception e) {
                        log.warn("解析用户权限 JSON 失败: {}", json, e);
                    }

                    String userScopes = "";
                    for (int i = 0; i < scopes.size(); i++) {
                        if (i == 0) {
                            userScopes = scopes.get(i);
                        } else {
                            userScopes += "," + scopes.get(i);
                        }
                    }

                    //将用户信息添加如请求头中
                    String finalUserScopes = userScopes;
                    ServerHttpRequest mutate = exchange.getRequest().mutate().headers(
                            headers -> {
                                //添加用户ID
                                headers.remove("LifeMind-User-Id");
                                headers.set("LifeMind-User-Id", userId);

                                //添加用户身份
                                headers.remove("LifeMind-User-Roles");
                                headers.set("LifeMind-User-Roles", jwt.getClaim("LifeMind-User-Roles").asString());

                                //添加用户权限
                                headers.remove("LifeMind-User-Scopes");
                                headers.set("LifeMind-User-Scopes", finalUserScopes);
                            }
                    ).build();

                    return filterChain.filter(exchange.mutate().request(mutate).build())
                            //把上面构造好的 SecurityContext 写进 Reactor Context，后面的过滤器就认为已认证
                            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)));
                });
    }
}
