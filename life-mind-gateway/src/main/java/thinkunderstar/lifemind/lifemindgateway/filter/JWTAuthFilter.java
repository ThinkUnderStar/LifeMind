package thinkunderstar.lifemind.lifemindgateway.filter;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.core.ParameterizedTypeReference;
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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import thinkunderstar.lifemind.lifemindgateway.common.Result;
import thinkunderstar.lifemind.lifemindgateway.util.ResponseUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RefreshScope
@Component
public class JWTAuthFilter implements WebFilter {
    private static final Logger log = LoggerFactory.getLogger(JWTAuthFilter.class);
    //用于把 Redis 里存的权限 JSON 解析成列表
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 用户权限在 Redis 中的 key 前缀，完整 key 为 {@code life-mind:{userId}:scopes}，
     * 必须与 account 服务登录时写入的 key 完全一致。
     */
    private static final String USER_SCOPES_KEY_PREFIX = "life-mind:";

    /**
     * 权限缓存有效期，与 account 服务写入时保持一致。
     */
    private static final Duration SCOPES_TTL = Duration.ofDays(7);

    /**
     * 回源 account 服务的超时时间：account 服务卡住时不能把网关请求一起拖死。
     */
    private static final Duration SCOPES_QUERY_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 缓存未命中的占位标记。
     *
     * <p>用它而不是 null：{@code defaultIfEmpty} 只在 Redis 里没有这个 key 时触发，
     * 而权限为空串是有效值（该用户确实没有权限），两者必须区分开，
     * 否则没权限的用户每次请求都会回源查库。
     */
    private static final String SCOPES_NOT_CACHED = "none";

    /**
     * 负载均衡的 URI scheme。设成 "lb" 后由 ReactiveLoadBalancerClientFilter
     * 把它替换成真实的服务实例地址。
     */
    private static final String LB_SCHEME = "lb";

    /**
     * account 服务在注册中心里的服务名，作为 URI 的 host 使用，
     * 负载均衡器就是靠这个值去找实例的。
     */
    private static final String ACCOUNT_SERVICE_ID = "life-mind-account";

    /**
     * account 服务权限接口的路径部分（注意不含 scheme 和 host）。
     */
    private static final String SCOPES_QUERY_PATH = "/auth/internal/permissions";

    private final ReactiveRedisTemplate<Object, Object> reactiveRedisTemplate;
    private final WebClient webClient;
    @Value("${jwt.key}")
    private String jwtKey;

    public JWTAuthFilter(ReactiveRedisTemplate<Object, Object> reactiveRedisTemplate, WebClient.Builder webClient) {
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.webClient = webClient.build();
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

        //Token 合法但没有 User-Id claim 时必须拦住，否则会出两个问题：
        //1. 拼接 Redis key 得到字面量 "life-mind:null:scopes"，
        //   所有缺 claim 的 Token 会共用同一个缓存 key，互相串权限；
        //2. 回源时 queryParam("userId", null) 不会抛异常，而是生成没有值的
        //   ?userId，account 服务按 Long 接收会直接报 400。
        //这种 Token 不可能是登录接口签发的，按无效 Token 处理。
        if (userId == null || userId.isBlank()) {
            return ResponseUtils.writeError(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "TOKEN_INVALID",
                    "Token 缺少用户信息"
            );
        }

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
        //这里必须返回 Mono<String>：错误分支返回的是 Mono<Void>，
        //用 <String>onErrorResume / <String>flatMap 显式指定类型，
        //否则类型推断会得出 Mono<Object>，跟方法的 Mono<Void> 返回类型对不上。
        return reactiveRedisTemplate.opsForValue().get(scopesKey(userId))
                .defaultIfEmpty(SCOPES_NOT_CACHED)
                //Redis 连接异常：直接写回错误响应，后面的 flatMap 不会再执行
                .onErrorResume(
                        RedisConnectionFailureException.class,
                        e -> {
                            log.error("连接 Redis 查询用户权限失败", e);
                            return ResponseUtils.<String>writeError(
                                    exchange,
                                    HttpStatus.INTERNAL_SERVER_ERROR,
                                    "SERVER_ERROR",
                                    "服务端出现异常"
                            );
                        }
                )
                .flatMap(cached -> {
                    //缓存命中：直接用缓存里的权限
                    if (!SCOPES_NOT_CACHED.equals(cached.toString())) {
                        return passThrough(exchange, filterChain, jwt, userId, securityContext, cached.toString());
                    }

                    //缓存未命中：回源 account 服务查权限
                    return fetchScopesFromAccount(userId)
                            .flatMap(scopes -> {
                                //回源失败：直接返回错误响应，不放行请求
                                if (scopes == null) {
                                    return ResponseUtils.<Void>writeError(
                                            exchange,
                                            HttpStatus.SERVICE_UNAVAILABLE,
                                            "SCOPES_UNAVAILABLE",
                                            "无法获取用户权限，请稍后重试"
                                    );
                                }

                                //回源成功：回填缓存后放行。
                                //空串是有效值（该用户确实没有任何权限），同样要缓存，
                                //否则没权限的用户每次请求都会回源查库。
                                return reactiveRedisTemplate.opsForValue()
                                        .set(scopesKey(userId), scopes, SCOPES_TTL)
                                        .thenReturn(scopes)
                                        //回填失败不影响本次请求：权限已经拿到了
                                        .onErrorResume(e -> {
                                            log.warn("回填用户权限缓存失败, userId={}", userId, e);
                                            return Mono.just(scopes);
                                        })
                                        .flatMap(loaded -> passThrough(
                                                exchange, filterChain, jwt, userId, securityContext, loaded));
                            });
                });
    }

    /**
     * 回源 account 服务查询用户权限，并校验返回的 {@link Result}。
     *
     * <p>返回 null 表示「回源失败」，调用方据此写回错误响应，即 fail-closed；
     * 返回空串表示「回源成功，但该用户没有任何权限」，属于正常结果，要放行。
     *
     * <p>校验规则与失败处理：
     * <ul>
     *     <li>请求异常（服务不可用、连接被拒）→ 失败</li>
     *     <li>超时（{@link #SCOPES_QUERY_TIMEOUT}）→ 失败，避免 account 服务卡住时拖死网关</li>
     *     <li>响应体为 null → 失败（响应结构不符合预期）</li>
     *     <li>业务码为 null 或 code=200 但 data 为 null → 失败（响应结构不符合预期）</li>
     *     <li>业务码非 200 → 放行但权限置空：这是 account 服务给出的权威答复
     *         （如 404 用户不存在、403 账号已禁用），用户本来就不该有权限</li>
     *     <li>code=200 且 data 为空串 → 放行，权限置空</li>
     * </ul>
     *
     * @param userId 用户 ID
     * @return 权限标识串；回源失败时返回 null
     */
    private Mono<String> fetchScopesFromAccount(String userId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        //scheme 与 host 必须分别设置：
                        //写成 .path("lb://life-mind-account/...") 的话，整个 "lb://..." 会被当成路径段，
                        //URI 会变成 lb:/life-mind-account/...（authority 为空），getHost() 返回 null。
                        //而 ReactiveLoadBalancerClientFilter 要求 uri.getScheme() 为 "lb"，
                        //并且用 uri.getHost() 作为服务名去 clientFactory 取实例，
                        //host 为 null 时它直接放行、不走负载均衡，最终请求会打到不存在的地址。
                        .scheme(LB_SCHEME)
                        .host(ACCOUNT_SERVICE_ID)
                        .path(SCOPES_QUERY_PATH)
                        .queryParam("userId", userId)
                        .build())
                .retrieve()
                //用 Result<String> 而不是裸 Result.class：裸类型下 getData() 返回 Object，
                //还得强转成 String，这里让反序列化直接给出正确类型
                .bodyToMono(new ParameterizedTypeReference<Result<String>>() {
                })
                .map(this::resolveScopes)
                .timeout(SCOPES_QUERY_TIMEOUT)
                //用 null 作为「回源失败」的标记，不能用空串：
                //空串是「该用户没有权限」的有效值，两者必须区分开
                .onErrorResume(e -> {
                    log.error("回源查询用户权限失败, userId={}", userId, e);
                    return Mono.empty();
                });
    }

    /**
     * 校验 account 服务返回的响应体，取出权限标识串。
     *
     * @param result account 服务的响应
     * @return 权限标识串；响应结构不符合预期时返回 null（表示回源失败）
     */
    private String resolveScopes(Result<String> result) {
        //1. 响应体本身不能为空
        if (result == null) {
            log.error("回源查询用户权限返回空响应体, 响应结构不符合预期");
            return null;
        }

        //2. 业务码不能为空
        if (result.getCode() == null) {
            log.error("回源查询用户权限返回空业务码, 响应结构不符合预期");
            return null;
        }

        //3. 业务码非 200：account 服务给出的权威答复（用户不存在、账号被禁用等），
        //   用户本来就不该有权限，按「无权限」处理并放行
        if (!result.isSuccess()) {
            log.warn("回源查询用户权限业务失败, code={}, message={}",
                    result.getCode(), result.getMessage());
            return "";
        }

        //4. code=200 但 data 为 null：响应结构不符合预期，按回源失败处理；
        //   注意 data 为空串是合法结果，走不到这里
        String data = result.getData();
        if (data == null) {
            log.error("回源查询用户权限返回空数据, 响应结构不符合预期");
            return null;
        }

        return data;
    }

    /**
     * 把用户信息与权限写入请求头，交给后续过滤器处理。
     *
     * @param scopes 权限标识串，逗号分隔；空串表示该用户没有任何权限
     */
    private Mono<Void> passThrough(ServerWebExchange exchange,
                                   WebFilterChain filterChain,
                                   DecodedJWT jwt,
                                   String userId,
                                   SecurityContext securityContext,
                                   String scopes) {
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
                    headers.set("LifeMind-User-Scopes", scopes);
                }
        ).build();

        return filterChain.filter(exchange.mutate().request(mutate).build())
                //把上面构造好的 SecurityContext 写进 Reactor Context，后面的过滤器就认为已认证
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)));
    }

    /**
     * 拼接用户权限在 Redis 中的 key，必须与 account 服务登录时写入的 key 完全一致，
     * 否则登录后权限永远查不到，每次请求都会回源。
     */
    private String scopesKey(String userId) {
        return USER_SCOPES_KEY_PREFIX + userId + ":scopes";
    }
}
