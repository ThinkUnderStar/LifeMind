package thinkunderstar.lifemind.lifemindschedule.dto.response;

import lombok.Data;

/**
 * 一个系列展开出的实例统计。
 *
 * <p>单独一个对象而不是塞进 {@code SeriesResponse}：它是聚合查询的结果，
 * 与系列本体来自两张表，混在一起会让人以为每个字段都是一次查询能拿到的。
 */
@Data
public class SeriesInstanceStat {

    /**
     * 系列ID
     */
    private Long seriesId;

    /**
     * 该系列未删除的实例条数
     */
    private Integer itemCount;

    /**
     * 最早一条实例的ID；实例是按时间升序批量插入的，所以它也是「第一次」
     */
    private Long firstItemId;

    /**
     * 最晚一条实例的ID
     */
    private Long lastItemId;
}
