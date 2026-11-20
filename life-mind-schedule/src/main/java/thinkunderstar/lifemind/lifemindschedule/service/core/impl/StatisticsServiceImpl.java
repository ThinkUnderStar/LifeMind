package thinkunderstar.lifemind.lifemindschedule.service.core.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.common.ResultCode;
import thinkunderstar.lifemind.lifemindschedule.dto.response.DailyStatResponse;
import thinkunderstar.lifemind.lifemindschedule.dto.response.OverviewResponse;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleItem;
import thinkunderstar.lifemind.lifemindschedule.exception.BusinessException;
import thinkunderstar.lifemind.lifemindschedule.mapper.ScheduleItemMapper;
import thinkunderstar.lifemind.lifemindschedule.service.core.StatisticsService;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.ScheduleItemWrapperService;
import thinkunderstar.lifemind.lifemindschedule.util.UserContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 统计服务实现。
 */
@Slf4j
@Service
public class StatisticsServiceImpl implements StatisticsService {

    private static final String TYPE_SCHEDULE = "schedule";
    private static final String TYPE_TODO = "todo";

    /**
     * 已取消：统计时排除。已完成算进总数（完成率的分母）
     */
    private static final int STATUS_DONE = 1;
    private static final int STATUS_CANCELLED = 2;

    /**
     * 默认看最近 7 天；区间上限 90 天（再长的柱状图前端也画不清，查询也会变慢）
     */
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_RANGE_DAYS = 90;

    private final ScheduleItemMapper itemMapper;
    private final ScheduleItemWrapperService itemWrapperService;

    public StatisticsServiceImpl(ScheduleItemMapper itemMapper,
                                 ScheduleItemWrapperService itemWrapperService) {
        this.itemMapper = itemMapper;
        this.itemWrapperService = itemWrapperService;
    }

    @Override
    public Result<List<DailyStatResponse>> daily(LocalDate startDate, LocalDate endDate) {
        Long userId = UserContext.currentUserId();
        LocalDate today = LocalDate.now();

        LocalDate start = startDate != null ? startDate : today.minusDays(DEFAULT_DAYS - 1L);
        LocalDate end = endDate != null ? endDate : today;

        if (end.isBefore(start)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "统计区间不能超过 " + MAX_RANGE_DAYS + " 天");
        }

        // 结束日取次日零点做开区间，避免 23:59:59 这类边界问题
        List<DailyStatResponse> stats = itemMapper.selectDailyStats(
                userId, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        return Result.success(stats);
    }

    @Override
    public Result<OverviewResponse> overview() {
        Long userId = UserContext.currentUserId();
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);
        LocalDateTime now = LocalDateTime.now();

        OverviewResponse overview = new OverviewResponse();

        // 今日日程：看开始时间。已取消的不计入，已完成的照常计入（今天确实有这件事）
        overview.setTodayScheduleCount(itemWrapperService.count(
                new LambdaQueryWrapper<ScheduleItem>()
                        .eq(ScheduleItem::getUserId, userId)
                        .eq(ScheduleItem::getItemType, TYPE_SCHEDULE)
                        .ne(ScheduleItem::getStatus, STATUS_CANCELLED)
                        .ge(ScheduleItem::getStartTime, todayStart)
                        .lt(ScheduleItem::getStartTime, tomorrowStart)));

        // 今日待办：看截止时间。没有截止时间的待办归不到某一天，不计入（与柱状图口径一致）
        LambdaQueryWrapper<ScheduleItem> todayTodo = new LambdaQueryWrapper<ScheduleItem>()
                .eq(ScheduleItem::getUserId, userId)
                .eq(ScheduleItem::getItemType, TYPE_TODO)
                .ne(ScheduleItem::getStatus, STATUS_CANCELLED)
                .ge(ScheduleItem::getDueTime, todayStart)
                .lt(ScheduleItem::getDueTime, tomorrowStart);
        overview.setTodayTodoCount(itemWrapperService.count(todayTodo));

        overview.setTodayTodoDoneCount(itemWrapperService.count(
                new LambdaQueryWrapper<ScheduleItem>()
                        .eq(ScheduleItem::getUserId, userId)
                        .eq(ScheduleItem::getItemType, TYPE_TODO)
                        .eq(ScheduleItem::getStatus, STATUS_DONE)
                        .ge(ScheduleItem::getDueTime, todayStart)
                        .lt(ScheduleItem::getDueTime, tomorrowStart)));

        // 逾期：未完成且截止时间已过。这条最该被看见，放在概览里而不是细节页
        overview.setOverdueTodoCount(itemWrapperService.count(
                new LambdaQueryWrapper<ScheduleItem>()
                        .eq(ScheduleItem::getUserId, userId)
                        .eq(ScheduleItem::getItemType, TYPE_TODO)
                        .eq(ScheduleItem::getStatus, 0)
                        .isNotNull(ScheduleItem::getDueTime)
                        .lt(ScheduleItem::getDueTime, now)));

        // 本周：周一到下周一
        LocalDateTime weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
        LocalDateTime weekEnd = weekStart.plusDays(7);

        long weekTodoCount = itemWrapperService.count(
                new LambdaQueryWrapper<ScheduleItem>()
                        .eq(ScheduleItem::getUserId, userId)
                        .eq(ScheduleItem::getItemType, TYPE_TODO)
                        .ne(ScheduleItem::getStatus, STATUS_CANCELLED)
                        .ge(ScheduleItem::getDueTime, weekStart)
                        .lt(ScheduleItem::getDueTime, weekEnd));
        long weekTodoDone = itemWrapperService.count(
                new LambdaQueryWrapper<ScheduleItem>()
                        .eq(ScheduleItem::getUserId, userId)
                        .eq(ScheduleItem::getItemType, TYPE_TODO)
                        .eq(ScheduleItem::getStatus, STATUS_DONE)
                        .ge(ScheduleItem::getDueTime, weekStart)
                        .lt(ScheduleItem::getDueTime, weekEnd));

        overview.setWeekTodoCount(weekTodoCount);
        overview.setWeekTodoDoneCount(weekTodoDone);
        overview.setWeekCompletionRate(completionRate(weekTodoDone, weekTodoCount));

        return Result.success(overview);
    }

    /**
     * 完成率：0~100，保留一位小数。
     *
     * <p>分母为 0 时返回 0 而不是 null：对卡片展示来说「本周没有待办」和「完成率 0%」
     * 用同一个数字表达就够了，避免前端多写一个判空分支。
     */
    private Double completionRate(long done, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(done)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
