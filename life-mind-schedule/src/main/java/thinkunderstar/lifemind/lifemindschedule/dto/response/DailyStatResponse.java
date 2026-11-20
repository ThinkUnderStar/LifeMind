package thinkunderstar.lifemind.lifemindschedule.dto.response;

import lombok.Data;

import java.time.LocalDate;

/**
 * 每日统计：柱状图的一个柱子。
 *
 * <p>字段名与 {@code ScheduleItemMapper#selectDailyStats} 的 SQL 别名通过
 * {@code @Results} 显式映射，不依赖驼峰转换开关。
 *
 * <p>只返回「有数据的日子」：没有事项的某天不会补 0 行。前端画连续柱状图时
 * 按自己的日期轴补齐即可——服务端补零需要先知道区间长度，反而更容易出错。
 */
@Data
public class DailyStatResponse {

    /**
     * 统计日期
     */
    private LocalDate date;

    /**
     * 当天日程数
     */
    private Long scheduleCount;

    /**
     * 当天待办总数（含已完成）
     */
    private Long todoCount;

    /**
     * 当天待办已完成数
     */
    private Long todoDoneCount;
}
