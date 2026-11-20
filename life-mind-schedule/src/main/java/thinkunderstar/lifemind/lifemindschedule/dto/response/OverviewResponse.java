package thinkunderstar.lifemind.lifemindschedule.dto.response;

import lombok.Data;

/**
 * 概览卡片数据：今天要做什么、这周做得怎么样。
 *
 * <p>前端顶部那排数字（今日日程 / 今日待办 / 逾期 / 本周完成率）一次拿齐，
 * 不用发四个请求。
 *
 * <p>{@code weekCompletionRate} 用 0~100 的百分数而不是 0~1：
 * 前端直接拼 "%"，避免各页面各自决定要不要乘 100。
 * 本周没有任何待办时返回 0，不返回 null——「没有」和「0%」对卡片展示是一回事。
 */
@Data
public class OverviewResponse {

    /**
     * 今日日程数（status != 2）
     */
    private Long todayScheduleCount;

    /**
     * 今日待办总数
     */
    private Long todayTodoCount;

    /**
     * 今日待办已完成数
     */
    private Long todayTodoDoneCount;

    /**
     * 已逾期未完成的待办数（截止时间早于当前，且 status=0）
     */
    private Long overdueTodoCount;

    /**
     * 本周待办总数（周一到周日）
     */
    private Long weekTodoCount;

    /**
     * 本周待办已完成数
     */
    private Long weekTodoDoneCount;

    /**
     * 本周待办完成率，0~100，保留一位小数
     */
    private Double weekCompletionRate;
}
