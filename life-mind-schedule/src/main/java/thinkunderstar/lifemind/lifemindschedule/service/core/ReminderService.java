package thinkunderstar.lifemind.lifemindschedule.service.core;

import thinkunderstar.lifemind.lifemindschedule.dto.response.ReminderResponse;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleItem;

import java.util.List;

/**
 * 提醒服务：提醒行的维护与到点投递。
 *
 * <p>提醒不由用户直接创建，而是「事项时间 + 提前偏移」算出来的从属数据。
 * 因此本接口没有 create/update，只有「按事项重算」与「按事项取消」——
 * 让调用方无法造出与事项时间对不上的提醒。
 */
public interface ReminderService {

    /**
     * 按事项重算提醒（先取消旧的未发送提醒，再按偏移生成新的）。
     *
     * <p>调用场景：创建事项、修改事项时间、修改提醒设置。
     *
     * <p>两条关键规则：
     * <ul>
     *     <li>事项没有可用的基准时间（日程没开始时间 / 待办没截止时间）时不生成提醒，
     *         只把旧的取消掉——算不出触发时刻，建了也没意义。接口层会先拦住这种请求
     *         （见 {@code ItemServiceImpl#requireReminderFeasible}），这里是最后一道防线，
     *         走到这里会打 warn 日志；</li>
     *     <li>已经过去的提醒不生成：否则扫描任务会在下一秒把它捞出来，
     *         用户刚建完事项就收到一条「提醒」。</li>
     * </ul>
     *
     * @param userId  用户ID
     * @param item    事项（必须有 id 与时间字段）
     * @param offsets 提前分钟数列表；null 表示不改（调用方自己判断），空列表表示全部取消
     */
    void rebuildForItem(Long userId, ScheduleItem item, List<Integer> offsets);

    /**
     * 取消某事项所有未发送的提醒。
     *
     * <p>调用场景：事项完成、取消、删除。
     */
    void cancelPending(Long userId, Long itemId);

    /**
     * 查询某事项的提醒（含已发送与已取消，按触发时间升序）。
     */
    List<ReminderResponse> listByItem(Long userId, Long itemId);

    /**
     * 查询未来若干天内待触发的提醒（跨事项，按时间升序）。
     *
     * @param days 天数，服务层会压到 1~30
     */
    List<ReminderResponse> upcoming(Long userId, int days);

    /**
     * 扫描到点提醒并投递到通知服务。
     *
     * <p>由定时任务调用，返回本次实际投递的条数（用于日志观察积压）。
     */
    int dispatchDueReminders();
}
