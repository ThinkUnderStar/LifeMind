package thinkunderstar.lifemind.lifemindschedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleReminder;

import java.util.List;

/**
 * 提醒表 Mapper。
 *
 * <p>扫描链路的三条语句都用手写 SQL，因为都要「带状态条件更新」，靠 QueryWrapper
 * 表达虽然可行但可读性差；更重要的是这三条语句的返回值（影响行数）就是并发控制手段：
 * 只有把 status 从 0 改成 1 成功的那一次调用，才有资格去发消息。
 */
public interface ScheduleReminderMapper extends BaseMapper<ScheduleReminder> {

    /**
     * 取出已经到点但还没发送的提醒（按时间先后）。
     *
     * <p>只查 status=0：已取消、已发送、发送失败的都不该再被捞出来。
     * 用 {@code remind_time <= NOW()} 是为了让「服务停机一段时间后重启」也能补发
     * 到点但错过的提醒，而不是永远跳过。
     *
     * @param limit 单次最多取多少条，避免积压时一次拉爆内存
     */
    @Select("""
            SELECT id, user_id, item_id, remind_time, offset_minutes, channel,
                   status, retry_count, sent_time, create_time, update_time, deleted
            FROM schedule_reminder
            WHERE status = 0
              AND deleted = 0
              AND remind_time <= NOW()
            ORDER BY remind_time ASC
            LIMIT #{limit}
            """)
    List<ScheduleReminder> selectDueReminders(@Param("limit") int limit);

    /**
     * 抢占一条提醒：把 0（待发送）改成 1（已发送），并记录发送时间。
     *
     * <p>这是整个扫描链路的并发闸门：多实例同时扫描时，只有一个实例能更新成功
     * （{@code WHERE status = 0} 让第二次更新影响 0 行），只有它去发消息。
     *
     * <p>为什么先占位再投递，而不是投递成功再改状态：
     * 先投递的话，两个实例会各发一次，用户收到两条重复提醒；
     * 先占位最坏是进程在占位后崩溃、这条没发出去（漏一条），比重复轰炸体验好。
     *
     * @return 影响行数：1-抢到，0-已被别的实例或用户操作处理掉
     */
    @Update("""
            UPDATE schedule_reminder
            SET status = 1,
                sent_time = NOW()
            WHERE id = #{id}
              AND status = 0
              AND deleted = 0
            """)
    int claim(@Param("id") Long id);

    /**
     * 投递失败的回退：计数 +1，未超上限退回待发送，超上限置为发送失败。
     *
     * <p>用一条 CASE 语句完成「重试还是放弃」的判断，避免「先查 retry_count 再决定」
     * 之间被别的线程改掉。
     *
     * <p>{@code sent_time = NULL} 是必要的：占位时已经写了一个近似时间，
     * 既然没发成功，就不能留下「已发送时间」这种自相矛盾的字段。
     *
     * @param id       提醒ID
     * @param maxRetry 最大重试次数（含本次）
     * @return 影响行数
     */
    @Update("""
            UPDATE schedule_reminder
            SET status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 3 ELSE 0 END,
                retry_count = retry_count + 1,
                sent_time = NULL
            WHERE id = #{id}
              AND status = 1
            """)
    int releaseOnFailure(@Param("id") Long id, @Param("maxRetry") int maxRetry);

    /**
     * 取消某个事项所有尚未发送的提醒。
     *
     * <p>事项被完成 / 取消 / 删除，或者用户改了提醒设置时调用。
     * 已经发送过的（status=1）保持原样：那是历史记录，不是待办。
     *
     * @param itemId 事项ID
     * @param userId 用户ID（冗余条件，防止误传别人的 itemId）
     * @return 被取消的条数
     */
    @Update("""
            UPDATE schedule_reminder
            SET status = 2
            WHERE item_id = #{itemId}
              AND user_id = #{userId}
              AND status = 0
              AND deleted = 0
            """)
    int cancelPendingByItem(@Param("itemId") Long itemId, @Param("userId") Long userId);
}
