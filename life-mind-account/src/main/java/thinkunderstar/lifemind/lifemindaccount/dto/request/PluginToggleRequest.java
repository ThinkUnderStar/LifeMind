package thinkunderstar.lifemind.lifemindaccount.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 插件启停请求参数。
 *
 * <p>只做「改状态」，不传用户 ID：操作的一定是当前登录用户自己，
 * 身份取自网关注入的请求头。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PluginToggleRequest {

    /**
     * 插件标识，如 schedule；必须是 plugin 表里已上架的 plugin_key。
     *
     * <p>待办不是独立插件，它随 schedule 一起启停，不要传 todo。
     */
    private String pluginKey;

    /**
     * true-启用 false-停用；不传按 null 处理，会在服务层被当作参数错误
     */
    private Boolean enabled;
}
