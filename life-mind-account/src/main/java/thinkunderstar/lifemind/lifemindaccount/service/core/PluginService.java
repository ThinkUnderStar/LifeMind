package thinkunderstar.lifemind.lifemindaccount.service.core;

import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.dto.response.PluginResponse;

import java.util.List;

/**
 * 插件启停服务。
 *
 * <p>职责边界：只维护「有哪些插件」与「某个用户启用了哪些」，
 * 不关心插件内部提供什么工具——那是 agent 侧按 plugin_key 前缀自行匹配的。
 */
public interface PluginService {

    /**
     * 查询插件市场列表，并标出当前用户是否启用。
     *
     * <p>只返回上架插件（status=1）。用户没配置过的插件按 default_enabled 折算，
     * 所以返回的 enabled 已经是最终状态。
     *
     * <p>操作的是当前登录用户自己，用户身份取自 SecurityContext，不接受前端传用户 ID。
     *
     * @return 按 sort 升序的插件列表
     */
    Result<List<PluginResponse>> listPlugins();

    /**
     * 启用或停用某个插件。
     *
     * <p>幂等：重复设置成同一状态不报错。写入后会失效 Redis 缓存（不是写新值），
     * 下一次请求由网关回源读到库里真实状态，避免事务回滚后缓存与库不一致。
     *
     * <p>同样是操作当前登录用户自己的配置。
     *
     * @param pluginKey 插件标识
     * @param enabled   true-启用 false-停用
     */
    Result<Void> setPluginEnabled(String pluginKey, boolean enabled);

    /**
     * 查询某用户当前生效的插件标识串（直接查库）。
     *
     * <p>登录时用：签发令牌的路径手里还没有缓存，直接取库更准确。
     *
     * @param userId 用户ID
     * @return 逗号分隔的插件标识，一个都没有时为空串
     */
    String queryEnabledPluginKeys(Long userId);

    /**
     * 把插件标识串写入 Redis。
     *
     * <p>key 与过期时间必须与网关读取时完全一致，否则每次请求都会回源。
     * 空串同样要缓存：它表示「这个用户一个插件都没启用」，是有效结果。
     *
     * @param userId     用户ID
     * @param pluginKeys 逗号分隔的插件标识
     */
    void cachePluginKeys(Long userId, String pluginKeys);

    /**
     * 内部接口：供网关在 Redis 读不到插件集合时回源。
     *
     * <p>与权限内部接口一致，先校验账号存在且未被禁用，避免已禁用账号
     * 通过该接口拿到插件集合。同样是「先读缓存、未命中回查并回填」。
     *
     * @param userId 用户ID
     * @return 逗号分隔的插件标识；用户没有启用任何插件时为空串
     */
    Result<String> getPluginKeysByUserId(Long userId);
}
