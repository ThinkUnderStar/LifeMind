package thinkunderstar.lifemind.lifemindgateway.filter;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import thinkunderstar.lifemind.lifemindgateway.util.ResponseUtils;

@Component
public class JWTAuthFilter implements WebFilter {
    private final ReactiveRedisTemplate<Object, Object> reactiveRedisTemplate;
    @Value("${jwt.key}")
    private String jwtKey;

    public JWTAuthFilter(ReactiveRedisTemplate<Object, Object> reactiveRedisTemplate) {
        this.reactiveRedisTemplate = reactiveRedisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain filterChain) {
        if (exchange.getRequest().getURI().getPath().startsWith("/auth/login")) {
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
        }catch (TokenExpiredException e) {
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
        reactiveRedisTemplate.opsForValue().get("life-mind:"+userId+":scopes").flatMap(json-> {

        })

        //将用户信息添加如请求头中
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

                }
        ).build();

        return filterChain.filter(exchange.mutate().request(mutate).build());
    }
}
