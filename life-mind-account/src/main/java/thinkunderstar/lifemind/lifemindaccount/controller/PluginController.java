package thinkunderstar.lifemind.lifemindaccount.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.common.ResultCode;
import thinkunderstar.lifemind.lifemindaccount.dto.request.PluginToggleRequest;
import thinkunderstar.lifemind.lifemindaccount.dto.response.PluginResponse;
import thinkunderstar.lifemind.lifemindaccount.exception.BusinessException;
import thinkunderstar.lifemind.lifemindaccount.service.core.PluginService;

import java.util.List;

/**
 * 插件启停接口。
 *
 * <p>本控制器下都是「当前登录用户管自己的插件」，共两个接口，都不接受前端传用户 ID：
 * <ul>
 *     <li>GET  /plugin/list     插件市场：上架插件 + 我是否启用</li>
 *     <li>POST /plugin/enable   启用 / 停用某个插件（幂等，同一个 body 重复提交结果一致）</li>
 * </ul>
 *
 * <p>给网关回源的内部接口不在这里，放在 {@code UserInfoController#internalPlugins}：
 * 内部接口统一走 {@code /user/internal/**}，方便安全配置与过滤器一处放行。
 */
@RestController
@RequestMapping("/plugin")
public class PluginController {

    private final PluginService pluginService;

    public PluginController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    /**
     * 插件市场列表。
     *
     * <p>只返回上架的插件，enabled 是把「没配置过」按默认值折算后的最终状态，
     * 前端直接按这一列渲染开关即可。
     */
    @GetMapping("/list")
    public Result<List<PluginResponse>> listPlugins() {
        return pluginService.listPlugins();
    }

    /**
     * 启用 / 停用插件。
     *
     * <p>幂等：重复提交同一状态不会报错，也不会刷新 update_time
     * （那个时间要当停用时间用）。
     *
     * <p>插件不存在返回 404，已下架返回 403；已下架的插件不能通过接口重新启用，
     * 只能由运维在库里改 status。
     */
    @PostMapping("/enable")
    public Result<Void> setPluginEnabled(@RequestBody PluginToggleRequest request) {
        //enabled 是可空 Boolean：显式传 false 与「没传」必须区分开，
        //没传说明前端调用有误，不能默认成停用
        if (request == null || request.getEnabled() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "enabled 不能为空");
        }
        return pluginService.setPluginEnabled(request.getPluginKey(), request.getEnabled());
    }
}
