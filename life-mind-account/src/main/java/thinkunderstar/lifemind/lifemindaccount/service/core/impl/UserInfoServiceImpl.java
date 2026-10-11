package thinkunderstar.lifemind.lifemindaccount.service.core.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.common.ResultCode;
import thinkunderstar.lifemind.lifemindaccount.entity.Permission;
import thinkunderstar.lifemind.lifemindaccount.entity.User;
import thinkunderstar.lifemind.lifemindaccount.entity.UserIdentity;
import thinkunderstar.lifemind.lifemindaccount.exception.BusinessException;
import thinkunderstar.lifemind.lifemindaccount.mapper.IdentityPermissionMapper;
import thinkunderstar.lifemind.lifemindaccount.service.core.UserInfoService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserIdentityService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserService;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserInfoServiceImpl implements UserInfoService {

    /**
     * 用户状态：1-正常（0 为禁用）
     */
    private static final int USER_STATUS_ENABLED = 1;

    /**
     * 用户权限在 Redis 中的 key 前缀，完整 key 为 {@code life-mind:{userId}:scopes}。
     * 登录时写入、网关读取、本服务回查后回填，三处必须保持一致。
     */
    private static final String USER_SCOPES_KEY_PREFIX = "life-mind:";

    /**
     * 权限缓存有效期，与登录时写入的保持一致。
     */
    private static final Duration USER_SCOPES_TTL = Duration.ofDays(7);

    private final UserService userService;
    private final UserIdentityService userIdentityService;
    private final IdentityPermissionMapper identityPermissionMapper;
    private final RedisTemplate<Object, Object> redisTemplate;

    public UserInfoServiceImpl(UserService userService,
                               UserIdentityService userIdentityService,
                               IdentityPermissionMapper identityPermissionMapper,
                               RedisTemplate<Object, Object> redisTemplate) {
        this.userService = userService;
        this.userIdentityService = userIdentityService;
        this.identityPermissionMapper = identityPermissionMapper;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Result<String> getPermissionsByUserId(Long userId) {

        // ========== 1. 参数校验 ==========
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不合法");
        }

        // ========== 2. 校验用户存在且未被禁用 ==========
        // 这里不校验密码，属于网关到本服务的内网调用；但仍要校验账号状态，
        // 避免已禁用账号通过该接口拿到权限标识。
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ENABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }

        // ========== 3. 先读缓存 ==========
        // 网关判定缓存未命中才会调过来，但缓存可能刚被写入，
        // 也可能为空串（该用户确实没有权限），两种情况都直接返回。
        Object cached = redisTemplate.opsForValue().get(scopesKey(userId));
        if (cached != null) {
            return Result.success(cached.toString());
        }

        // ========== 4. 缓存未命中，回查数据库 ==========
        String scopes = loadScopesByIdentityIds(loadIdentityIds(userId));

        // 回填缓存，与登录时的 key、过期时间保持一致，
        // 避免网关每次请求都回查数据库。空串同样会被缓存。
        cacheScopes(userId, scopes);

        return Result.success(scopes);
    }

    @Override
    public String loadScopesByIdentityIds(Collection<Long> identityIds) {
        // 获取这些身份拥有的全部权限（SQL 用 DISTINCT 去重，空集合时天然返回空列表），
        // 再对权限标识过滤 null 并去重兜底
        return identityPermissionMapper.selectPermissionsByIdentityIds(identityIds)
                .stream()
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(","));
    }

    @Override
    public void cacheScopes(Long userId, String scopes) {
        redisTemplate.opsForValue().set(scopesKey(userId), scopes, USER_SCOPES_TTL);
    }

    /**
     * 查询用户绑定的全部身份 ID。
     */
    private List<Long> loadIdentityIds(Long userId) {
        return userIdentityService.list(
                new LambdaQueryWrapper<UserIdentity>()
                        .eq(UserIdentity::getUserId, userId)
        ).stream()
                .map(UserIdentity::getIdentityId)
                .collect(Collectors.toList());
    }

    /**
     * 拼接用户权限在 Redis 中的 key，登录、网关、回查三处必须一致。
     */
    private String scopesKey(Long userId) {
        return USER_SCOPES_KEY_PREFIX + userId + ":scopes";
    }
}
