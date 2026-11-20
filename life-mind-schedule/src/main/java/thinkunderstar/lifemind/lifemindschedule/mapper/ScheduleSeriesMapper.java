package thinkunderstar.lifemind.lifemindschedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import thinkunderstar.lifemind.lifemindschedule.dto.response.SeriesInstanceStat;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleSeries;

import java.util.List;

/**
 * 重复系列表 Mapper。
 *
 * <p>只有「一次查出多个系列的实例统计」需要手写 SQL：
 * 逐系列调一次 {@code selectCount} 会变成 N+1，而系列列表本来就一次要几十条。
 */
public interface ScheduleSeriesMapper extends BaseMapper<ScheduleSeries> {

    /**
     * 批量统计每个系列展开出多少条实例，以及最早 / 最晚一条的 id。
     *
     * <p>为什么 MIN(id) 就是「第一次」：实例是在同一次创建里按时间升序批量插入的，
     * 自增 id 与时间同序。重建时先删后插，删的是未来那一段、插入的也是未来那一段，
     * 相对顺序不变，所以这个推断仍然成立。
     *
     * <p>手写 SQL 不受 {@code @TableLogic} 约束，{@code deleted = 0} 必须自己写。
     *
     * @param userId    用户ID
     * @param seriesIds 系列ID列表，调用方保证非空且不超过 100 个
     * @return 每个有实例的系列一行；没有实例的系列不会出现在结果里
     */
    @Select("""
            <script>
            SELECT series_id     AS seriesId,
                   COUNT(*)      AS itemCount,
                   MIN(id)       AS firstItemId,
                   MAX(id)       AS lastItemId
            FROM schedule_item
            WHERE user_id = #{userId}
              AND deleted = 0
              AND series_id IN
              <foreach collection="seriesIds" item="sid" open="(" separator="," close=")">
                  #{sid}
              </foreach>
            GROUP BY series_id
            </script>
            """)
    List<SeriesInstanceStat> selectInstanceStats(@Param("userId") Long userId,
                                                 @Param("seriesIds") List<Long> seriesIds);
}
