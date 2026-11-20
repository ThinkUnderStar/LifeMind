package thinkunderstar.lifemind.lifemindschedule.service.core;

import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.dto.response.DailyStatResponse;
import thinkunderstar.lifemind.lifemindschedule.dto.response.OverviewResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 可视化数据服务。
 *
 * <p>只提供数据，不画图：前端要柱状图、折线图还是日历热力图都由它自己决定，
 * 后端如果按某种图形返回数据，换图就得改接口。
 *
 * <p>所有统计口径都以「用户自己的事项」为界，不做跨用户聚合。
 */
public interface StatisticsService {

    /**
     * 按日聚合：某段时间内每天的日程数 / 待办数 / 待办完成数。
     *
     * <p>不传日期时默认最近 7 天（含今天），符合「一周柱状图」的默认视图。
     *
     * @param startDate 区间起点（含），可空
     * @param endDate   区间终点（含），可空
     */
    Result<List<DailyStatResponse>> daily(LocalDate startDate, LocalDate endDate);

    /**
     * 概览：今日日程数、今日待办完成情况、逾期待办、本周完成率。
     */
    Result<OverviewResponse> overview();
}
