package thinkunderstar.lifemind.lifemindaccount.service.core;

import thinkunderstar.lifemind.lifemindaccount.common.Result;

import java.util.Collection;

/**
 * 用户信息维度的服务。
 *
 * <p>当前承载用户权限标识的查询与缓存：登录时把权限写入 Redis 供网关读取，
 * 网关缓存未命中时再回查数据库并回填。这两条路径必须共用同一份 Redis key 与过期时间，
 * 所以统一收敛到本服务，避免 key 拼接逻辑在多处各存一份而静默失配。
 */
public interface UserInfoService {

    /**
     * 根据用户 ID 查询该用户拥有的全部权限标识（已去重）。
     *
     * <p>内部接口，供网关在 Redis 中查不到用户权限时回查数据库使用。
     * 返回的是逗号分隔的权限标识串，与登录时写入 Redis 的格式一致；
     * 用户存在但没有任何权限时返回空串（而不是 null），
     * 这样网关可以区分「查过了，确实没权限」和「还没查过」。
     *
     * @param userId 用户 ID
     * @return 权限标识串，多个用英文逗号分隔，无权限时为空串
     */
    Result<String> getPermissionsByUserId(Long userId);

    /**
     * 根据身份 ID 集合加载权限标识串。
     *
     * <p>链路：identity_permission 关联 permission 查权限。
     * 权限按权限标识去重，防止同一权限被多个身份重复授予时返回重复项；
     * 无身份或身份下无权限时返回空串。
     *
     * <p>收身份 ID 集合而不是用户 ID：登录路径手里已经有身份 ID（签令牌要用），
     * 直接传进来可以少查一次 user_identity。
     *
     * @param identityIds 身份 ID 集合，可为空
     * @return 权限标识串，多个用英文逗号分隔，无权限时为空串
     */
    String loadScopesByIdentityIds(Collection<Long> identityIds);

    /**
     * 按统一的 key 与过期时间把权限写入 Redis 缓存。空串同样会被缓存，
     * 表示「该用户确实没有权限」，避免网关每次都回查数据库。
     *
     * @param userId 用户 ID
     * @param scopes 权限标识串，多个用英文逗号分隔
     */
    void cacheScopes(Long userId, String scopes);
}
