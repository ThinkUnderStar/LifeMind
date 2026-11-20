package thinkunderstar.lifemind.lifemindschedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import thinkunderstar.lifemind.lifemindschedule.dto.response.DailyStatResponse;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事项表 Mapper。
 *
 * <p>单表条件查询与分页走 {@link BaseMapper}；只有「按日聚合」需要手写 SQL，
 * 因为它要做 GROUP BY 与条件求和，用 QueryWrapper 表达不出来。
 */
public interface ScheduleItemMapper extends BaseMapper<ScheduleItem> {

    /**
     * 按日聚合一段时间内的事项，供前端画柱状图。
     *
     * <p>聚合口径：用 {@code COALESCE(start_time, due_time)} 作为「这件事发生在哪天」。
     * 日程看开始时间，待办看截止时间；两者都没有的待办不会出现在结果里——
     * 它没有可归入的日期，硬算成「创建日」会让图表在用户没安排任何事的某天冒出柱子。
     *
     * <p>为什么用结束时间做开区间（{@code < endTime}）而不是 {@code <=}：
     * 调用方传的是「结束日的次日零点」，这样不必担心 23:59:59.999 这类边界。
     *
     * <p>注意：COALESCE 会让 {@code idx_user_start / idx_user_due} 失效，实际走
     * {@code idx_user_deleted} 再回表过滤。单用户事项量级在千行以内，可接受；
     * 真要优化得加生成列与索引，不值得在第一版引入。
     *
     * <p>手写 SQL 不受 {@code @TableLogic} 约束，{@code deleted = 0} 必须自己写。
     *
     * @param userId    用户ID
     * @param startTime 区间起点（含）
     * @param endTime   区间终点（不含）
     * @return 按日期升序的每日统计；没有数据时返回空列表
     */
    @Select("""
            SELECT DATE(COALESCE(start_time, due_time))                                  AS stat_date,
                   CAST(SUM(CASE WHEN item_type = 'schedule' THEN 1 ELSE 0 END) AS SIGNED) AS schedule_count,
                   CAST(SUM(CASE WHEN item_type = 'todo' THEN 1 ELSE 0 END) AS SIGNED)     AS todo_count,
                   CAST(SUM(CASE WHEN item_type = 'todo' AND status = 1 THEN 1 ELSE 0 END)
                       AS SIGNED)                                                     AS todo_done_count
            FROM schedule_item
            WHERE user_id = #{userId}
              AND deleted = 0
              AND COALESCE(start_time, due_time) >= #{startTime}
              AND COALESCE(start_time, due_time) < #{endTime}
            GROUP BY DATE(COALESCE(start_time, due_time))
            ORDER BY stat_date ASC
            """)
    @Results(id = "dailyStatResult", value = {
            @Result(column = "stat_date", property = "date"),
            @Result(column = "schedule_count", property = "scheduleCount"),
            @Result(column = "todo_count", property = "todoCount"),
            @Result(column = "todo_done_count", property = "todoDoneCount"),
    })
    List<DailyStatResponse> selectDailyStats(@Param("userId") Long userId,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime);
}
