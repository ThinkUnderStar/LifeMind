package thinkunderstar.lifemind.lifemindschedule.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.dto.response.DailyStatResponse;
import thinkunderstar.lifemind.lifemindschedule.dto.response.OverviewResponse;
import thinkunderstar.lifemind.lifemindschedule.service.core.StatisticsService;

import java.time.LocalDate;
import java.util.List;

/**
 * 可视化数据接口。
 *
 * <p>只给数据、不画图：柱状图、折线图、热力图都由前端按这两个接口的返回值自行渲染。
 *
 * <p>两个接口：
 * <ul>
 *     <li>GET /statistics/daily     按日聚合（柱状图的数据源）</li>
 *     <li>GET /statistics/overview  概览卡片（今日 / 本周 / 逾期）</li>
 * </ul>
 */
@RestController
@RequestMapping("/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    /**
     * 按日聚合。
     *
     * <p>不传日期默认最近 7 天（含今天）。只返回有数据的日子，
     * 前端按自己的日期轴补零即可。
     *
     * @param startDate 起点（含），格式 yyyy-MM-dd
     * @param endDate   终点（含），格式 yyyy-MM-dd
     */
    @GetMapping("/daily")
    public Result<List<DailyStatResponse>> daily(
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return statisticsService.daily(startDate, endDate);
    }

    /**
     * 概览：今日日程数、今日待办完成情况、逾期待办数、本周完成率。
     */
    @GetMapping("/overview")
    public Result<OverviewResponse> overview() {
        return statisticsService.overview();
    }
}
