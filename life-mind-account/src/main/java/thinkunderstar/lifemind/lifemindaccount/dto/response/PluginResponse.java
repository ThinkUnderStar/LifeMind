package thinkunderstar.lifemind.lifemindaccount.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 插件市场里的一项：插件自身信息 + 当前用户是否启用。
 *
 * <p>把 {@code enabled} 一起返回，前端列表渲染不用再关联两个接口。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PluginResponse {

    /**
     * 插件标识：schedule / bill / health
     *
     * <p>与 agent 侧 SERVICE_TOOLS 的 key 一一对应；待办并入 schedule，不单列。
     */
    private String pluginKey;

    /**
     * 展示名，如「日程与待办」
     */
    private String name;

    /**
     * 一句话说明
     */
    private String description;

    /**
     * 图标标识
     */
    private String icon;

    /**
     * 版本号
     */
    private String version;

    /**
     * 当前用户是否启用。
     *
     * <p>已把「没配置过」按插件的 default_enabled 折算过，
     * 前端拿到的就是最终状态，不需要再判断默认值。
     */
    private Boolean enabled;
}
