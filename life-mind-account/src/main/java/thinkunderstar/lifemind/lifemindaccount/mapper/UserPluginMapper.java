package thinkunderstar.lifemind.lifemindaccount.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import thinkunderstar.lifemind.lifemindaccount.entity.UserPlugin;

import java.util.List;

public interface UserPluginMapper extends BaseMapper<UserPlugin> {

    /**
     * 查询某用户当前生效的插件标识。
     *
     * <p>合并规则用 {@code COALESCE(up.enabled, p.default_enabled)}：
     * 用户配置过就以配置为准，没配置过就跟随插件的默认值。
     * 这样不需要在注册时批量插入行，也不会出现「新加一个默认启用的插件，
     * 老用户反而没生效」的问题。
     *
     * <p>只返回上架插件（{@code p.status = 1}）：下架等于拔掉，
     * 用户配置留在表里，但不再参与工具绑定。
     *
     * @param userId 用户ID
     * @return 启用的插件标识，按 sort / id 升序；一个都没有时返回空列表
     */
    @Select("""
            SELECT p.plugin_key
            FROM plugin p
            LEFT JOIN user_plugin up
                   ON up.plugin_id = p.id
                  AND up.user_id = #{userId}
            WHERE p.status = 1
              AND COALESCE(up.enabled, p.default_enabled) = 1
            ORDER BY p.sort ASC, p.id ASC
            """)
    List<String> selectEnabledPluginKeys(@Param("userId") Long userId);
}
