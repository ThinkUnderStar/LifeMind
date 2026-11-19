package thinkunderstar.lifemind.lifemindaccount.service.core.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.common.ResultCode;
import thinkunderstar.lifemind.lifemindaccount.dto.response.PluginResponse;
import thinkunderstar.lifemind.lifemindaccount.entity.Plugin;
import thinkunderstar.lifemind.lifemindaccount.entity.User;
import thinkunderstar.lifemind.lifemindaccount.entity.UserPlugin;
import thinkunderstar.lifemind.lifemindaccount.exception.BusinessException;
import thinkunderstar.lifemind.lifemindaccount.mapper.PluginMapper;
import thinkunderstar.lifemind.lifemindaccount.mapper.UserPluginMapper;
import thinkunderstar.lifemind.lifemindaccount.security.LifeMindUserDetails;
import thinkunderstar.lifemind.lifemindaccount.service.core.PluginService;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 插件启停服务实现。
 *
 * <p>缓存约定与权限（scopes）完全一致：key 为 {@code life-mind:{userId}:plugins}，
 * 由本服务写入、网关读取并回查、过期时间 7 天。三处任何一处改动都必须同步，
 * 否则会出现「刚启用插件，本轮对话里模型仍然看不到」。
 */
@Slf4j
@Service
public class PluginServiceImpl implements PluginService {

    /**
     * 用户级缓存在 Redis 中的 key 前缀，完整 key 为 {@code life-mind:{userId}:xxx}
     */
    private static final String USER_KEY_PREFIX = "life-mind:";

    /**
     * 插件集合缓存的 key 后缀，完整 key 为 {@code life-mind:{userId}:plugins}
     */
    private static final String USER_PLUGINS_KEY_SUFFIX = ":plugins";

    /**
     * 插件集合缓存有效期，与权限缓存保持一致
     */
    private static final Duration USER_PLUGINS_TTL = Duration.ofDays(7);

    /**
     * 插件上架状态：1-上架（0 为下架）
     */
    private static final int PLUGIN_STATUS_ONLINE = 1;

    /**
     * 用户插件启用状态：1-启用（0 为停用）
     */
    private static final int USER_PLUGIN_ENABLED = 1;

    /**
     * 账号状态：1-正常（0 为禁用）
     */
    private static final int USER_STATUS_ENABLED = 1;

    private final PluginMapper pluginMapper;
    private final UserPluginMapper userPluginMapper;
    private final UserService userService;
    private final RedisTemplate<Object, Object> redisTemplate;

    public PluginServiceImpl(PluginMapper pluginMapper,
                             UserPluginMapper userPluginMapper,
                             UserService userService,
                             RedisTemplate<Object, Object> redisTemplate) {
        this.pluginMapper = pluginMapper;
        this.userPluginMapper = userPluginMapper;
        this.userService = userService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Result<List<PluginResponse>> listPlugins() {

        // ========== 1. 取当前登录用户 ==========
        Long userId = currentUserId();

        // ========== 2. 查上架插件 ==========
        // 插件总数很少（个位数），一次全查再内存合并，比写一条 join 更直白；
        // 顺带避免 join 结果里出现重复行还要在 Java 里再去一次重
        List<Plugin> plugins = pluginMapper.selectList(
                new LambdaQueryWrapper<Plugin>()
                        .eq(Plugin::getStatus, PLUGIN_STATUS_ONLINE)
                        .orderByAsc(Plugin::getSort)
                        .orderByAsc(Plugin::getId)
        );
        if (plugins.isEmpty()) {
            return Result.success(List.of());
        }

        // ========== 3. 查该用户的配置，按插件ID索引 ==========
        Map<Long, Integer> userConfig = userPluginMapper.selectList(
                        new LambdaQueryWrapper<UserPlugin>()
                                .eq(UserPlugin::getUserId, userId)
                ).stream()
                .collect(Collectors.toMap(
                        UserPlugin::getPluginId,
                        UserPlugin::getEnabled,
                        //同一插件理论上只有一行（唯一索引兜底），保留最后一条即可
                        (existing, ignored) -> existing
                ));

        // ========== 4. 合并：配置过以配置为准，没配置过按插件默认值 ==========
        // 这一步必须在这里做完，前端拿到的 enabled 才是最终状态，
        // 否则每个前端都要复制一遍「null 表示跟随默认值」的判断
        List<PluginResponse> result = plugins.stream()
                .map(plugin -> new PluginResponse(
                        plugin.getPluginKey(),
                        plugin.getName(),
                        plugin.getDescription(),
                        plugin.getIcon(),
                        plugin.getVersion(),
                        resolveEnabled(plugin, userConfig.get(plugin.getId()))
                ))
                .toList();

        return Result.success(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> setPluginEnabled(String pluginKey, boolean enabled) {

        // ========== 1. 参数校验 ==========
        Long userId = currentUserId();
        if (pluginKey == null || pluginKey.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "插件标识不能为空");
        }

        // ========== 2. 校验插件存在且上架 ==========
        Plugin plugin = pluginMapper.selectOne(
                new LambdaQueryWrapper<Plugin>().eq(Plugin::getPluginKey, pluginKey.trim())
        );
        if (plugin == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "插件不存在");
        }
        if (!Objects.equals(plugin.getStatus(), PLUGIN_STATUS_ONLINE)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "插件已下架");
        }

        // ========== 3. 写入或更新用户配置 ==========
        int target = enabled ? USER_PLUGIN_ENABLED : 0;
        UserPlugin existing = userPluginMapper.selectOne(
                new LambdaQueryWrapper<UserPlugin>()
                        .eq(UserPlugin::getUserId, userId)
                        .eq(UserPlugin::getPluginId, plugin.getId())
        );

        if (existing == null) {
            UserPlugin record = new UserPlugin();
            record.setUserId(userId);
            record.setPluginId(plugin.getId());
            record.setEnabled(target);
            try {
                userPluginMapper.insert(record);
            } catch (DuplicateKeyException ignored) {
                // 并发下两个请求同时首次配置：唯一索引挡住了后一个，
                // 这里降级成更新，结果与「先查再写」等价，不需要让用户重试
                userPluginMapper.update(null,
                        new LambdaUpdateWrapper<UserPlugin>()
                                .eq(UserPlugin::getUserId, userId)
                                .eq(UserPlugin::getPluginId, plugin.getId())
                                .set(UserPlugin::getEnabled, target)
                );
            }
        } else if (!Objects.equals(existing.getEnabled(), target)) {
            // 状态没变就不写：避免把 update_time 刷成「用户刚刚又点了一次」，
            // 这个时间将来要当停用时间用
            userPluginMapper.update(null,
                    new LambdaUpdateWrapper<UserPlugin>()
                            .eq(UserPlugin::getId, existing.getId())
                            .set(UserPlugin::getEnabled, target)
            );
        }

        // ========== 4. 失效缓存，而不是写新值 ==========
        // 这里是 @Transactional 内部：如果改成「查一遍新集合并写进 Redis」，
        // 一旦事务提交失败回滚，缓存里留下的是库里并不存在的新状态，
        // 网关会据此继续给模型绑定已停用的工具，且要等 TTL（7 天）才自愈。
        // 删 key 最简单也最安全：下一次请求必然回源查库，读到的就是真实状态。
        // 代价只是每个用户改插件后多一次回源，插件表只有个位数行，可以忽略。
        try {
            redisTemplate.delete(pluginsKey(userId));
        } catch (Exception e) {
            // 删缓存失败不影响本次操作：库里已经是新状态，
            // 最坏情况是旧缓存活到过期，下次改插件时会再次删除
            log.warn("失效插件缓存失败, userId={}", userId, e);
        }

        return Result.success();
    }

    @Override
    public String queryEnabledPluginKeys(Long userId) {
        // selectEnabledPluginKeys 已把「没配置过」按 default_enabled 折算过，
        // 并且过滤掉了下架插件，这里只做拼接
        List<String> keys = userPluginMapper.selectEnabledPluginKeys(userId);
        if (keys == null || keys.isEmpty()) {
            return "";
        }
        return String.join(",", keys);
    }

    @Override
    public void cachePluginKeys(Long userId, String pluginKeys) {
        // 空串也要写：它表示「该用户一个插件都没启用」，
        // 与「缓存里没有这个 key」是两种状态，后者会触发回源
        redisTemplate.opsForValue().set(
                pluginsKey(userId),
                pluginKeys == null ? "" : pluginKeys,
                USER_PLUGINS_TTL
        );
    }

    @Override
    public Result<String> getPluginKeysByUserId(Long userId) {

        // ========== 1. 参数校验 ==========
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不合法");
        }

        // ========== 2. 校验用户存在且未被禁用 ==========
        // 与权限内部接口同口径：网关到本服务属于内网调用，不校验密码，
        // 但账号状态必须校验，否则禁用账号能继续拿到插件集合
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (!Objects.equals(user.getStatus(), USER_STATUS_ENABLED)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }

        // ========== 3. 先读缓存 ==========
        Object cached = redisTemplate.opsForValue().get(pluginsKey(userId));
        if (cached != null) {
            return Result.success(cached.toString());
        }

        // ========== 4. 缓存未命中，回查数据库并回填 ==========
        String keys = queryEnabledPluginKeys(userId);
        cachePluginKeys(userId, keys);

        return Result.success(keys);
    }

    /**
     * 计算某插件对某用户的最终启用状态：用户配置过以配置为准，否则跟随插件默认值。
     *
     * @param plugin    插件
     * @param userValue 用户在 user_plugin 里的 enabled；null 表示从未配置
     * @return 是否启用
     */
    private boolean resolveEnabled(Plugin plugin, Integer userValue) {
        if (userValue != null) {
            return userValue == USER_PLUGIN_ENABLED;
        }
        return Objects.equals(plugin.getDefaultEnabled(), USER_PLUGIN_ENABLED);
    }

    /**
     * 取当前登录用户ID。
     *
     * <p>口径与 {@code UserInfoServiceImpl#currentUserId()} 完全一致：
     * 只认 {@code HeaderAuthenticationFilter} 写进 SecurityContext 的 principal，
     * 不接受前端传用户 ID，取不到即 401。
     *
     * <p>没有抽成公共工具类，是为了不改动已验证的用户模块代码；
     * 两处的判断逻辑相同，将来若要合并，一起搬到 util 即可。
     *
     * @return 当前登录用户ID
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof LifeMindUserDetails details) {
                Long userId = details.getUserId();
                if (userId != null && userId > 0) {
                    return userId;
                }
            }
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
    }

    /**
     * 拼接插件集合在 Redis 中的 key，必须与网关读取时完全一致。
     *
     * @param userId 用户ID
     * @return 完整 key
     */
    private String pluginsKey(Long userId) {
        return USER_KEY_PREFIX + userId + USER_PLUGINS_KEY_SUFFIX;
    }
}
