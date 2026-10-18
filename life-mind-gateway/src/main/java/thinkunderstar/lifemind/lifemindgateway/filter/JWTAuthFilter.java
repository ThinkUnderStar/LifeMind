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
     * JWT 负载里令牌版本号的声明名，由 account 服务登录时写入。
     * 用户每次改密这个值都会在 account 侧 +1，对不上就说明令牌已作废。
     */
    private static final String TOKEN_VERSION_CLAIM = "LifeMind-Token-Version";

    /**
     * 用户级缓存在 Redis 中的 key 前缀，完整 key 为 {@code life-mind:{userId}:xxx}，
     * 必须与 account 服务登录时写入的 key 完全一致。
     */
    private static final String USER_KEY_PREFIX = "life-mind:";

    /**
     * 权限缓存的 key 后缀，完整 key 为 {@code life-mind:{userId}:scopes}。
     */
    private static final String USER_SCOPES_KEY_SUFFIX = ":scopes";

    /**
     * 权限缓存有效期，与 account 服务写入时保持一致。
     */
    private static final Duration SCOPES_TTL = Duration.ofDays(7);

    /**
     * 令牌版本号缓存的 key 后缀，完整 key 为 {@code life-mind:{userId}:token-version}，
     * 必须与 account 服务登录、改密时写入的 key 完全一致。
     */
    private static final String USER_TOKEN_VERSION_KEY_SUFFIX = ":token-version";

    /**
     * 令牌版本号缓存有效期，与 account 服务写入时保持一致。
     */
    private static final Duration TOKEN_VERSION_TTL = Duration.ofDays(7);

    /**
     * 令牌版本号取不到时的标记值：JWT 里的版本号一定是 >= 0 的整数，
     * 所以用 -1 表示「没查到 / 回源失败」，与「版本号恰好是 0」区分开。
     *
     * <p>拿到这个值时一律拒绝请求（fail-closed）：校验不出来不能当成校验通过。
     */
    private static final int TOKEN_VERSION_UNAVAILABLE = -1;

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
    private static final String SCOPES_QUERY_PATH = "/user/internal/permissions";

    /**
     * account 服务令牌版本接口的路径部分（注意不含 scheme 和 host）。
     */
    private static final String TOKEN_VERSION_QUERY_PATH = "/user/internal/token-version";

    /**
     * 回源 account 服务查询令牌版本号的超时时间，与权限一致。
     */
    private static final Duration TOKEN_VERSION_QUERY_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 登录接口的路径前缀，覆盖密码登录与手机 / 邮箱验证码登录。
     * 这些接口在登录前调用，没有 Token，必须跳过 JWT 校验。
     */
    private static final String LOGIN_PATH_PREFIX = "/life-mind/account/auth/login";

    /**
     * 注册接口的完整路径，同样在拿到 Token 之前调用。
     */
    private static final String REGISTER_PATH = "/life-mind/account/auth/register";

    /**
     * 验证码发送接口的路径前缀，同样在登录前调用，不需要 Token。
     */
    private static final String SEND_CODE_PATH_PREFIX = "/life-mind/account/auth/code/send";

    /**
     * account 服务的查询权限接口（内部接口），跳过 JWT 校验。
     *
     * <p>这个路径不需要在过滤器里校验 Token：它是网关自己在 Redis 权限缓存未命中时
     * 回源调用的，调用方是 {@link #fetchScopesFromAccount(String)}（直接走 lb:// 到服务实例，
     * 本来就不经过这条过滤器链），而外部请求由网关 Security 里的 denyAll 挡掉，
     * 放行到授权层依然会被拒绝（fail-closed）。
     */
    private static final String INTERNAL_PERMISSIONS_PATH = "/life-mind/account/user/internal/permissions";

    /**
     * account 服务的查询令牌版本接口（内部接口），跳过 JWT 校验。
     *
     * <p>与 {@link #INTERNAL_PERMISSIONS_PATH} 同理：网关自己在缓存未命中时回源调用它，
     * 走的是 {@code lb://} 到服务实例，不经过这条过滤器链；
     * 外部请求由网关 Security 里的 denyAll 挡掉。
     */
    private static final String INTERNAL_TOKEN_VERSION_PATH = "/life-mind/account/user/internal/token-version";

    /**
     * 访问MinIO的公开的桶的路径前缀
     */
    private static final String MINIO_PUBLIC_PATH_PREFIX = "/minio/life-mind-public";

    /**
     * 获取人机验证图
     */
    private static final String CAPTCHA_PATH_PREFIX = "/life-mind/account/captcha";

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
        String path = exchange.getRequest().getURI().getPath();
        if (path.equals(REGISTER_PATH)
                || path.equals(INTERNAL_PERMISSIONS_PATH)
                || path.equals(INTERNAL_TOKEN_VERSION_PATH)
                || path.startsWith(LOGIN_PATH_PREFIX)
                || path.startsWith(SEND_CODE_PATH_PREFIX)
                || path.startsWith(CAPTCHA_PATH_PREFIX)
                || path.startsWith(MINIO_PUBLIC_PATH_PREFIX)
        ) {
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
        String userId = jwt.getClaim("LifeMind-User-Id") + "";

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

        //先校验令牌版本号，再查权限：版本对不上说明令牌已被改密作废，没必要继续往下走
        return checkTokenVersion(exchange, jwt, userId)
                .flatMap(passed -> Boolean.TRUE.equals(passed)
                        //版本一致：继续原有流程（查权限后放行）
                        ? checkScopes(exchange, filterChain, jwt, userId, securityContext)
                        //版本不一致 / 无法校验：checkTokenVersion 里已经写过错误响应，到此结束
                        : Mono.empty());
    }

    /**
     * 校验 JWT 里的令牌版本号与用户当前的版本号是否一致。
     *
     * <p>版本号由 account 服务在登录签令牌时写进 JWT，改密后库里 +1 并刷新 Redis，
     * 所以版本对不上就说明这个令牌是改密之前签发的，必须作废——
     * 「改密让旧 JWT 全部失效」就是靠这一步实现的。
     *
     * <p>取当前版本号的顺序与 account 服务一致：先读 Redis，读不到（或缓存内容不是数字）
     * 再回查 account 的内部接口，并把回查结果回填缓存。
     * 读缓存失败、回源失败都按「取不到版本号」处理，一律拒绝请求（fail-closed），
     * 不能把「校验不出来」变成「校验通过」。
     *
     * @return true-版本一致，可以继续；false-已拒绝，错误响应已在方法内写回，调用方直接结束链路
     */
    private Mono<Boolean> checkTokenVersion(ServerWebExchange exchange, DecodedJWT jwt, String userId) {
        Integer jwtVersion = jwt.getClaim(TOKEN_VERSION_CLAIM).asInt();

        //登录接口一定会写入版本号声明；缺这个声明（或声明不是数字）说明令牌
        //不是本系统登录接口签发的，按无效令牌处理，不能当成版本 0 放行
        if (jwtVersion == null) {
            log.warn("Token 缺少令牌版本声明, userId={}", userId);
            return ResponseUtils.writeError(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "TOKEN_INVALID",
                    "Token 缺少令牌版本信息"
            ).thenReturn(false);
        }

        //先读缓存：版本号按字符串写入，缓存内容不是数字时按未命中处理，回源重新取一份
        Mono<Integer> currentVersion = reactiveRedisTemplate.opsForValue().get(tokenVersionKey(userId))
                .flatMap(cached -> Mono.justOrEmpty(parseTokenVersion(cached)))
                .switchIfEmpty(fetchTokenVersionFromAccount(userId))
                //回源失败：拿不到当前版本号，用哨兵值表示「无法校验」
                .defaultIfEmpty(TOKEN_VERSION_UNAVAILABLE)
                //Redis 连接异常等：同样按「无法校验」处理，不放行
                .onErrorResume(e -> {
                    log.error("查询用户令牌版本失败, userId={}", userId, e);
                    return Mono.just(TOKEN_VERSION_UNAVAILABLE);
                });

        return currentVersion.flatMap(version -> {
            //1. 取不到当前版本号：无法判断令牌是否还有效，拒绝
            if (version == TOKEN_VERSION_UNAVAILABLE) {
                log.error("无法获取用户令牌版本, userId={}", userId);
                return ResponseUtils.writeError(
                        exchange,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "TOKEN_VERSION_UNAVAILABLE",
                        "无法校验登录状态，请稍后重试"
                ).thenReturn(false);
            }

            //2. 版本对不上：令牌已被改密作废（或账号已注销 / 禁用），要求重新登录
            if (!version.equals(jwtVersion)) {
                log.info("令牌版本已失效, userId={}, tokenVersion={}, currentVersion={}",
                        userId, jwtVersion, version);
                return ResponseUtils.writeError(
                        exchange,
                        HttpStatus.UNAUTHORIZED,
                        "TOKEN_REVOKED",
                        "登录状态已失效，请重新登录"
                ).thenReturn(false);
            }

            //3. 版本一致：放行
            return Mono.just(true);
        });
    }

    /**
     * 查询用户权限，并把用户信息与权限交给后续过滤器。
     *
     * <p>权限取值顺序：先读 Redis，读不到再回查 account 服务并回填缓存。
     *
     * @return 放行请求；查询失败时写回错误响应后结束
     */
    private Mono<Void> checkScopes(ServerWebExchange exchange, WebFilterChain filterChain, DecodedJWT jwt,
                                   String userId, SecurityContext securityContext) {
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
     * 回源 account 服务查询用户当前的令牌版本号，并在拿到后回填缓存。
     *
     * <p>返回空 Mono 表示「取不到」：服务不可用、超时、响应结构不符合预期，
     * 或 account 明确答复用户不存在 / 账号被禁用（业务码非 200）。
     * 调用方统一按「无法校验登录状态」拒绝请求，即 fail-closed。
     *
     * <p>不用 0 表示取不到：0 是合法版本号（新用户的初始值），两者必须区分开。
     *
     * @param userId 用户 ID
     */
    private Mono<Integer> fetchTokenVersionFromAccount(String userId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        //scheme 与 host 的写法与权限接口一致，原因见 fetchScopesFromAccount 的注释
                        .scheme(LB_SCHEME)
                        .host(ACCOUNT_SERVICE_ID)
                        .path(TOKEN_VERSION_QUERY_PATH)
                        .queryParam("userId", userId)
                        .build())
                .retrieve()
                //与权限接口同样用参数化类型，让反序列化直接给出 Integer，省掉一次强转
                .bodyToMono(new ParameterizedTypeReference<Result<Integer>>() {
                })
                .flatMap(result -> resolveTokenVersion(result, userId))
                .timeout(TOKEN_VERSION_QUERY_TIMEOUT)
                //空 Mono 就是「取不到」的标记，异常与超时在这里统一转成它
                .onErrorResume(e -> {
                    log.error("回源查询用户令牌版本失败, userId={}", userId, e);
                    return Mono.empty();
                });
    }

    /**
     * 校验 account 服务返回的响应体，取出令牌版本号并回填缓存。
     *
     * @param result account 服务的响应
     * @param userId 用户 ID，用于拼接回填缓存的 key
     * @return 令牌版本号；响应结构不符合预期或业务失败时返回空 Mono（表示取不到）
     */
    private Mono<Integer> resolveTokenVersion(Result<Integer> result, String userId) {
        //1. 响应体与业务码不能为空
        if (result == null || result.getCode() == null) {
            log.error("回源查询用户令牌版本返回异常响应, 响应结构不符合预期");
            return Mono.empty();
        }

        //2. 业务码非 200：account 服务给出的权威答复（用户不存在、账号被禁用等）。
        //   这种账号不该再通过令牌校验，按「取不到」处理，上层会拒绝该请求。
        if (!result.isSuccess()) {
            log.warn("回源查询用户令牌版本业务失败, code={}, message={}",
                    result.getCode(), result.getMessage());
            return Mono.empty();
        }

        //3. code=200 但 data 为 null：响应结构不符合预期，按取不到处理
        Integer tokenVersion = result.getData();
        if (tokenVersion == null) {
            log.error("回源查询用户令牌版本返回空数据, 响应结构不符合预期");
            return Mono.empty();
        }

        //4. 回源成功：回填缓存，避免每次请求都回源；回填失败不影响本次校验
        return reactiveRedisTemplate.opsForValue()
                .set(tokenVersionKey(userId), String.valueOf(tokenVersion), TOKEN_VERSION_TTL)
                .thenReturn(tokenVersion)
                .onErrorResume(e -> {
                    log.warn("回填用户令牌版本缓存失败, userId={}", userId, e);
                    return Mono.just(tokenVersion);
                });
    }

    /**
     * 把缓存里读到的令牌版本号解析成整数。
     *
     * @param cached 缓存里的值
     * @return 版本号；缓存内容不是数字时返回 null，调用方按「未命中」处理，回源重新取一份
     */
    private Integer parseTokenVersion(Object cached) {
        if (cached == null) {
            return null;
        }
        try {
            return Integer.valueOf(cached.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("令牌版本缓存内容不是数字, value={}", cached);
            return null;
        }
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
        return USER_KEY_PREFIX + userId + USER_SCOPES_KEY_SUFFIX;
    }

    /**
     * 拼接令牌版本号在 Redis 中的 key，必须与 account 服务登录、改密时写入的 key 完全一致，
     * 否则校验令牌版本时永远读不到缓存，每次请求都会回源。
     */
    private String tokenVersionKey(String userId) {
        return USER_KEY_PREFIX + userId + USER_TOKEN_VERSION_KEY_SUFFIX;
    }
}
