package thinkunderstar.lifemind.lifemindschedule.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.common.ResultCode;
import thinkunderstar.lifemind.lifemindschedule.dto.response.ReminderResponse;
import thinkunderstar.lifemind.lifemindschedule.exception.BusinessException;
import thinkunderstar.lifemind.lifemindschedule.service.core.ReminderService;
import thinkunderstar.lifemind.lifemindschedule.util.UserContext;

import java.util.List;

/**
 * 提醒接口（只读）。
 *
 * <p>提醒不提供增删改：它是「事项时间 + 提前偏移」算出来的从属数据，
 * 单独改会让提醒与事项时间对不上。要改提醒，走 {@code PUT /item/update} 的
 * {@code remindOffsets} 字段。
 *
 * <p>两个接口：
 * <ul>
 *     <li>GET /reminder/list?itemId=   某事项的全部提醒（含已发送历史）</li>
 *     <li>GET /reminder/upcoming?days= 未来 N 天内待触发的提醒（跨事项）</li>
 * </ul>
 */
@RestController
@RequestMapping("/reminder")
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /**
     * 查询某事项的提醒，按触发时间升序。
     *
     * <p>包含已发送、已取消、发送失败的记录：前端展示「提醒历史」时需要它们。
     */
    @GetMapping("/list")
    public Result<List<ReminderResponse>> listByItem(@RequestParam("itemId") Long itemId) {
        if (itemId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "事项ID不能为空");
        }
        // userId 由 Service 从 SecurityContext 取，这里不需要传
        return Result.success(reminderService.listByItem(UserContext.currentUserId(), itemId));
    }

    /**
     * 未来若干天内待触发的提醒。
     *
     * <p>默认 7 天，服务层会压到 1~30 天。只返回 {@code status=0} 的，
     * 已发送的不该出现在「即将提醒」里。
     */
    @GetMapping("/upcoming")
    public Result<List<ReminderResponse>> upcoming(
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return Result.success(reminderService.upcoming(UserContext.currentUserId(), days));
    }
}
