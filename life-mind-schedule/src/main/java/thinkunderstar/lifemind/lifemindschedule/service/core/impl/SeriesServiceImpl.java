package thinkunderstar.lifemind.lifemindschedule.service.core.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.common.ResultCode;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesCreateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesDeleteRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesQueryRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesUpdateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.response.SeriesInstanceStat;
import thinkunderstar.lifemind.lifemindschedule.dto.response.SeriesResponse;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleItem;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleSeries;
import thinkunderstar.lifemind.lifemindschedule.exception.BusinessException;
import thinkunderstar.lifemind.lifemindschedule.mapper.ScheduleSeriesMapper;
import thinkunderstar.lifemind.lifemindschedule.service.core.ReminderService;
import thinkunderstar.lifemind.lifemindschedule.service.core.SeriesService;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.ScheduleItemWrapperService;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.ScheduleSeriesWrapperService;
import thinkunderstar.lifemind.lifemindschedule.util.RecurrenceExpander;
import thinkunderstar.lifemind.lifemindschedule.util.UserContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 重复系列服务实现。
 *
 * <p>核心决定：**写时展开**。创建时一次性把规则算成实体事项行，之后每个实例都是普通事项，
 * 提醒扫描、日视图、周视图、概览、按日统计一行都不用改。
 *
 * <p>代价写在这里，避免以后有人以为是 bug：
 * <ul>
 *     <li>单条实例被单独改过时间后，它与规则的对应关系就断了；
 *         之后对这个系列「改规则」会把这条实例按规则重算回来。</li>
 *     <li>「改全部 / 改以后」只影响未来未完成的实例，已经发生或已完成的保持原样。</li>
 *     <li>{@code future} 范围改的是系列模板本身，所以系列记录与锚点之前的实例
 *         不再是同一份内容——实例才是每一条的真实内容，系列只是「以后怎么排」。</li>
 * </ul>
 */
@Slf4j
@Service
public class SeriesServiceImpl implements SeriesService {

    /**
     * 事项类型
     */
    private static final String TYPE_SCHEDULE = "schedule";
    private static final String TYPE_TODO = "todo";

    /**
     * 修改 / 删除的范围
     */
    private static final String SCOPE_ALL = "all";
    private static final String SCOPE_FUTURE = "future";

    /**
     * 频率：只有 WEEKLY 认 byDay
     */
    private static final String FREQ_WEEKLY = "WEEKLY";

    /**
     * 系列状态
     */
    private static final int STATUS_ACTIVE = 0;
    private static final int STATUS_ENDED = 1;

    /**
     * 事项状态：未完成
     */
    private static final int ITEM_PENDING = 0;

    /**
     * 默认值：中优先级、非全天
     */
    private static final int DEFAULT_PRIORITY = 1;
    private static final int DEFAULT_ALL_DAY = 0;

    /**
     * 终止日期最多往后推几年：再远就是「另一个习惯」了，
     * 而且一次展开 100 条的上限也拦不住日期本身跑偏（比如传了 2099 年）
     */
    private static final int MAX_UNTIL_YEARS = 2;

    /**
     * 提醒偏移上限：7 天。与 ItemCreateRequest 保持一致
     */
    private static final int MAX_REMINDER_OFFSET = 10080;

    /**
     * 列表最多返回多少条：不分页，但要有个上限兜底
     */
    private static final int MAX_LIST_SIZE = 100;

    private final ScheduleSeriesWrapperService seriesWrapperService;
    private final ScheduleItemWrapperService itemWrapperService;
    private final ScheduleSeriesMapper seriesMapper;
    private final ReminderService reminderService;

    public SeriesServiceImpl(ScheduleSeriesWrapperService seriesWrapperService,
                             ScheduleItemWrapperService itemWrapperService,
                             ScheduleSeriesMapper seriesMapper,
                             ReminderService reminderService) {
        this.seriesWrapperService = seriesWrapperService;
        this.itemWrapperService = itemWrapperService;
        this.seriesMapper = seriesMapper;
        this.reminderService = reminderService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<SeriesResponse> create(SeriesCreateRequest request) {
        Long userId = UserContext.currentUserId();

        String requestId = trimToNull(request.getRequestId());
        if (requestId != null) {
            ScheduleSeries existing = findByIdempotentKey(userId, requestId);
            if (existing != null) {
                // 幂等命中：不重复展开。这类重试最容易出事——agent 超时重发一次，
                // 用户就会看到两套「每周三健身」，一共 24 条
                log.info("创建系列幂等命中：userId={} requestId={} seriesId={}",
                        userId, requestId, existing.getId());
                return Result.success(buildResponse(userId, existing));
            }
        }

        ScheduleSeries series = new ScheduleSeries();
        series.setUserId(userId);
        series.setItemType(request.getItemType());
        series.setTitle(request.getTitle().trim());
        series.setContent(request.getContent());
        series.setLocation(request.getLocation());
        series.setPriority(request.getPriority() == null ? DEFAULT_PRIORITY : request.getPriority());
        series.setAllDay(request.getAllDay() == null ? DEFAULT_ALL_DAY : request.getAllDay());
        series.setTags(request.getTags());
        series.setFreq(request.getFreq());
        series.setIntervalVal(request.getInterval() == null ? 1 : request.getInterval());
        series.setByDay(toByDayCsv(request.getByDay()));
        series.setUntilDate(request.getUntilDate());
        series.setCountLimit(request.getCountLimit());
        series.setBaseTime(request.getBaseTime());
        series.setDurationMinutes(request.getDurationMinutes());
        series.setRemindOffsets(formatOffsets(request.getRemindOffsets()));
        series.setStatus(STATUS_ACTIVE);
        series.setRequestId(requestId);
        series.setSource(UserContext.currentClient());

        // 必须在 normalizeByType 之前判：那里会把非 WEEKLY 的 byDay 静默清空，
        // 而「用户传了星期几、接口返回成功、日子却按每天排」是最难发现的一类错
        requireByDayScope(request.getFreq(), request.getByDay());
        normalizeByType(series);
        validateSeries(series);

        try {
            seriesWrapperService.save(series);
        } catch (DuplicateKeyException e) {
            // 并发下两个相同 requestId 同时通过了预检查，唯一索引在这里兜底
            ScheduleSeries existing = requestId == null ? null : findByIdempotentKey(userId, requestId);
            if (existing != null) {
                log.info("创建系列并发幂等命中：userId={} requestId={} seriesId={}",
                        userId, requestId, existing.getId());
                return Result.success(buildResponse(userId, existing));
            }
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "requestId 已被占用（对应系列可能已被删除），请换一个 requestId 重试");
        }

        int created = materialize(userId, series, null);
        log.info("重复系列已创建：seriesId={} userId={} freq={} 间隔={} 展开={} 条 source={}",
                series.getId(), userId, series.getFreq(), series.getIntervalVal(),
                created, series.getSource());

        return Result.success(buildResponse(userId, series));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<SeriesResponse> update(SeriesUpdateRequest request) {
        Long userId = UserContext.currentUserId();
        ScheduleSeries series = requireOwnedSeries(userId, request.getId());

        LocalDateTime anchor = null;
        if (SCOPE_FUTURE.equals(request.getScope())) {
            ScheduleItem anchorItem = requireAnchorItem(userId, series.getId(), request.getAnchorItemId());
            anchor = baseTimeOf(anchorItem);
        }

        applyContent(series, request);
        boolean ruleChanged = applyRule(series, request);
        // applyRule 之后 series.freq 才是最终值，这里判的是「最终频率 + 本次传的星期几」的组合
        requireByDayScope(series.getFreq(), request.getByDay());
        // 规则可能被改成不合法的组合（比如 WEEKLY 的 byDay 留着、freq 改成 DAILY），
        // 所以改完必须再校验一次，不能只依赖创建时的校验
        normalizeByType(series);
        validateSeries(series);

        // updateById 跳过 null 字段，清空得单独走 update(wrapper)
        seriesWrapperService.updateById(series);
        boolean clearByDay = !FREQ_WEEKLY.equals(series.getFreq());
        boolean clearRemind = series.getRemindOffsets() == null;
        clearSeriesColumns(userId, series.getId(), clearByDay, clearRemind);

        if (ruleChanged) {
            // 规则变了就必须重建：实例是物化的实体行，不同步的话日历上还是旧日子
            int removed = removeFutureInstances(userId, series, anchor);
            int added = materialize(userId, series, anchor);
            log.info("系列规则已变更并重建：seriesId={} userId={} scope={} 删除={} 新建={}",
                    series.getId(), userId, request.getScope(), removed, added);
        } else {
            int touched = applyContentToFutureInstances(userId, series, anchor, request);
            if (request.getRemindOffsets() != null) {
                rebuildRemindersForFutureInstances(userId, series, anchor, parseOffsets(series.getRemindOffsets()));
            }
            log.info("系列内容已变更：seriesId={} userId={} scope={} 影响={} 条",
                    series.getId(), userId, request.getScope(), touched);
        }

        return Result.success(buildResponse(userId, series));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(SeriesDeleteRequest request) {
        Long userId = UserContext.currentUserId();
        ScheduleSeries series = requireOwnedSeries(userId, request.getId());

        if (SCOPE_ALL.equals(request.getScope())) {
            int removed = removeFutureInstances(userId, series, null);
            seriesWrapperService.removeById(series.getId());
            log.info("重复系列已删除：seriesId={} userId={} 连带删除未来实例={} 条",
                    series.getId(), userId, removed);
            return Result.success();
        }

        ScheduleItem anchorItem = requireAnchorItem(userId, series.getId(), request.getAnchorItemId());
        LocalDateTime anchor = baseTimeOf(anchorItem);
        int removed = removeFutureInstances(userId, series, anchor);

        LocalDate cutoff = anchor.toLocalDate().minusDays(1);
        if (cutoff.isBefore(series.getBaseTime().toLocalDate())) {
            // 从第一次起就砍掉，等于整条系列作废。留着一条「从来没有发生过」的规则
            // 会让用户在列表里看到一堆 0 条实例的空壳
            seriesWrapperService.removeById(series.getId());
            log.info("系列从第一次起被终止，整条删除：seriesId={} userId={}", series.getId(), userId);
        } else {
            // 用「终止日期提前一天」表达「到此为止」，而不是只把 status 置成已结束：
            // 后者说不清「结束在哪一天」，用户回看时会以为规则还在、只是不排了
            series.setUntilDate(cutoff);
            series.setStatus(STATUS_ENDED);
            seriesWrapperService.updateById(series);
            log.info("系列已从锚点终止：seriesId={} userId={} untilDate={} 删除实例={} 条",
                    series.getId(), userId, cutoff, removed);
        }
        return Result.success();
    }

    @Override
    public Result<List<SeriesResponse>> list(SeriesQueryRequest request) {
        Long userId = UserContext.currentUserId();
        SeriesQueryRequest query = request == null ? new SeriesQueryRequest() : request;

        List<ScheduleSeries> seriesList = seriesWrapperService.list(
                new LambdaQueryWrapper<ScheduleSeries>()
                        .eq(ScheduleSeries::getUserId, userId)
                        .eq(StringUtils.hasText(query.getItemType()),
                                ScheduleSeries::getItemType, query.getItemType())
                        .eq(query.getStatus() != null, ScheduleSeries::getStatus, query.getStatus())
                        .orderByDesc(ScheduleSeries::getId)
                        .last("LIMIT " + MAX_LIST_SIZE));

        Map<Long, SeriesInstanceStat> stats = statsOf(userId,
                seriesList.stream().map(ScheduleSeries::getId).toList());

        List<SeriesResponse> responses = seriesList.stream()
                .map(series -> toResponse(series, stats.get(series.getId())))
                .toList();
        return Result.success(responses);
    }

    @Override
    public Result<SeriesResponse> detail(Long seriesId) {
        Long userId = UserContext.currentUserId();
        ScheduleSeries series = requireOwnedSeries(userId, seriesId);
        return Result.success(buildResponse(userId, series));
    }

    // ==================== 展开与实例维护 ====================

    /**
     * 按规则展开成实体事项。
     *
     * @param from 起始时刻（含），null 表示从系列锚点开始。改「以后」时传锚点时刻，
     *             保证重建范围与删除范围严格对齐——不一致就会漏建或建重复
     * @return 实际新建的条数
     */
    private int materialize(Long userId, ScheduleSeries series, LocalDateTime from) {
        List<RecurrenceExpander.Occurrence> occurrences = RecurrenceExpander.expand(
                series.getFreq(), series.getIntervalVal(), series.getByDay(),
                series.getUntilDate(), series.getCountLimit(), series.getBaseTime());

        LocalDateTime now = LocalDateTime.now();
        List<Integer> offsets = parseOffsets(series.getRemindOffsets());

        List<ScheduleItem> items = new ArrayList<>();
        for (RecurrenceExpander.Occurrence occurrence : occurrences) {
            LocalDateTime time = occurrence.time();
            if (from != null && time.isBefore(from)) {
                continue;
            }
            // 已经过去的时刻不补：补出来的表现是「刚建完就收到一堆提醒」
            if (!time.isAfter(now)) {
                continue;
            }
            items.add(buildItem(userId, series, occurrence.index(), time));
        }

        if (items.isEmpty()) {
            log.info("系列没有可展开的未来实例：seriesId={} baseTime={} freq={}",
                    series.getId(), series.getBaseTime(), series.getFreq());
            return 0;
        }

        itemWrapperService.saveBatch(items);

        // 提醒逐个建：每个实例有自己的时刻，不是一条提醒管全部。
        // 已经过去的那几个偏移会在 rebuildForItem 内部被过滤掉
        if (!offsets.isEmpty()) {
            for (ScheduleItem item : items) {
                reminderService.rebuildForItem(userId, item, offsets);
            }
        }
        return items.size();
    }

    private ScheduleItem buildItem(Long userId, ScheduleSeries series, int index, LocalDateTime time) {
        ScheduleItem item = new ScheduleItem();
        item.setUserId(userId);
        item.setItemType(series.getItemType());
        item.setTitle(series.getTitle());
        item.setContent(series.getContent());
        item.setLocation(series.getLocation());
        item.setPriority(series.getPriority());
        item.setAllDay(series.getAllDay());
        item.setTags(series.getTags());
        item.setStatus(ITEM_PENDING);
        item.setSeriesId(series.getId());
        item.setSeriesIndex(index);
        // 来源沿用系列创建时的来源，而不是当前调用方：
        // 重建可能由另一个入口触发，改掉来源会让「这条是谁建的」这个审计信息失真
        item.setSource(series.getSource());

        if (TYPE_SCHEDULE.equals(series.getItemType())) {
            item.setStartTime(time);
            if (series.getDurationMinutes() != null && series.getDurationMinutes() > 0) {
                item.setEndTime(time.plusMinutes(series.getDurationMinutes()));
            }
        } else {
            item.setDueTime(time);
        }

        // 刻意不写 request_id：系列一次展开 12 条，共用同一个幂等键会直接撞
        // uk_user_request_id；每条实例也不需要单独的幂等键——重试保护在系列那一层已经做了
        return item;
    }

    /**
     * 删除未来实例（保留已经发生过的）。
     *
     * <p>不限状态：重建的语义是「这个系列的未来重来一遍」，提前标记完成的那次也在范围内。
     * 想保住某一次的完成记录，就不要改这个系列的规则。
     *
     * @param from 起始时刻（含），null 表示所有未来实例
     * @return 删除条数
     */
    private int removeFutureInstances(Long userId, ScheduleSeries series, LocalDateTime from) {
        LambdaQueryWrapper<ScheduleItem> wrapper = futureInstancesWrapper(userId, series, from);
        List<ScheduleItem> doomed = itemWrapperService.list(wrapper.select(ScheduleItem::getId));
        if (doomed.isEmpty()) {
            return 0;
        }

        List<Long> ids = doomed.stream().map(ScheduleItem::getId).toList();
        // 先取消提醒再删：反过来的话会留下指向已删事项的待发送提醒
        for (Long id : ids) {
            reminderService.cancelPending(userId, id);
        }
        itemWrapperService.removeByIds(ids);
        return ids.size();
    }

    /**
     * 未来实例的条件：所属系列 + 时刻晚于现在 + （可选）不早于锚点。
     *
     * <p>日程看 start_time、待办看 due_time——用错列会让待办的系列一条都查不出来，
     * 因为待办的 start_time 恒为 null。
     */
    private LambdaQueryWrapper<ScheduleItem> futureInstancesWrapper(Long userId,
                                                                   ScheduleSeries series,
                                                                   LocalDateTime from) {
        LambdaQueryWrapper<ScheduleItem> wrapper = new LambdaQueryWrapper<ScheduleItem>()
                .eq(ScheduleItem::getUserId, userId)
                .eq(ScheduleItem::getSeriesId, series.getId());

        LocalDateTime now = LocalDateTime.now();
        if (TYPE_SCHEDULE.equals(series.getItemType())) {
            wrapper.gt(ScheduleItem::getStartTime, now);
            if (from != null) {
                wrapper.ge(ScheduleItem::getStartTime, from);
            }
        } else {
            wrapper.gt(ScheduleItem::getDueTime, now);
            if (from != null) {
                wrapper.ge(ScheduleItem::getDueTime, from);
            }
        }
        return wrapper;
    }

    /**
     * 把内容改动批量写到未来未完成的实例上。
     *
     * <p>只写未完成的：改标题不该把「上周已经做完的那次」也改掉，历史就该保持当时的样子。
     */
    private int applyContentToFutureInstances(Long userId,
                                              ScheduleSeries series,
                                              LocalDateTime anchor,
                                              SeriesUpdateRequest request) {
        LambdaQueryWrapper<ScheduleItem> wrapper = futureInstancesWrapper(userId, series, anchor)
                .eq(ScheduleItem::getStatus, ITEM_PENDING);
        List<ScheduleItem> items = itemWrapperService.list(wrapper);
        if (items.isEmpty()) {
            return 0;
        }

        for (ScheduleItem item : items) {
            if (request.getTitle() != null) {
                item.setTitle(series.getTitle());
            }
            if (request.getContent() != null) {
                item.setContent(series.getContent());
            }
            if (request.getLocation() != null) {
                item.setLocation(series.getLocation());
            }
            if (request.getPriority() != null) {
                item.setPriority(series.getPriority());
            }
            if (request.getAllDay() != null) {
                item.setAllDay(series.getAllDay());
            }
            if (request.getTags() != null) {
                item.setTags(series.getTags());
            }
        }
        itemWrapperService.updateBatchById(items);
        return items.size();
    }

    /**
     * 按新的偏移重建未来实例的提醒。
     *
     * <p>空列表表示取消全部提醒，走 cancelPending 而不是 rebuildForItem：
     * 后者对空列表是「什么都不做」，旧的提醒会留着继续响。
     */
    private void rebuildRemindersForFutureInstances(Long userId,
                                                    ScheduleSeries series,
                                                    LocalDateTime anchor,
                                                    List<Integer> offsets) {
        List<ScheduleItem> items = itemWrapperService.list(
                futureInstancesWrapper(userId, series, anchor).eq(ScheduleItem::getStatus, ITEM_PENDING));
        for (ScheduleItem item : items) {
            if (offsets.isEmpty()) {
                reminderService.cancelPending(userId, item.getId());
            } else {
                reminderService.rebuildForItem(userId, item, offsets);
            }
        }
    }

    // ==================== 校验与归一 ====================

    /**
     * 按类型清理不属于自己的字段。
     *
     * <p>待办没有地点、没有全天、没有时长，留着这些值会让展开出的实例带着
     * 「待办却有地点」的脏数据。
     */
    private void normalizeByType(ScheduleSeries series) {
        if (TYPE_TODO.equals(series.getItemType())) {
            series.setLocation(null);
            series.setAllDay(DEFAULT_ALL_DAY);
            series.setDurationMinutes(null);
        }
        // byDay 只对 WEEKLY 有意义，其他频率留着它会让展开器读到不该用的字段
        if (!FREQ_WEEKLY.equals(series.getFreq())) {
            series.setByDay(null);
        }
        if (TYPE_TODO.equals(series.getItemType()) && series.getBaseTime() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "待办的重复需要一个截止时刻作为锚点");
        }
        if (TYPE_SCHEDULE.equals(series.getItemType()) && series.getBaseTime() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "日程的重复需要一个开始时刻作为锚点");
        }
    }

    /**
     * 规则合法性校验。
     *
     * <p>重点是「必须有终点」：允许无限重复就必须有后台任务滚动补实例，
     * 而那个任务挂了是静默失败——用户在第 N+1 天突然看不到日程，且没有任何报错。
     */
    private void validateSeries(ScheduleSeries series) {
        if (series.getBaseTime() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "锚点时刻不能为空");
        }
        if (series.getUntilDate() == null && series.getCountLimit() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "重复必须有终点：请给出次数上限 countLimit 或终止日期 untilDate");
        }

        LocalDate baseDate = series.getBaseTime().toLocalDate();
        if (series.getUntilDate() != null) {
            if (series.getUntilDate().isBefore(baseDate)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "终止日期不能早于锚点日期");
            }
            if (series.getUntilDate().isAfter(baseDate.plusYears(MAX_UNTIL_YEARS))) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "终止日期最多在锚点之后 " + MAX_UNTIL_YEARS + " 年");
            }
        }

        if (series.getFreq() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "重复频率不能为空");
        }
        if (series.getIntervalVal() == null || series.getIntervalVal() < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "重复间隔至少是 1");
        }

        if (series.getRemindOffsets() != null) {
            for (Integer offset : parseOffsets(series.getRemindOffsets())) {
                if (offset < 0 || offset > MAX_REMINDER_OFFSET) {
                    throw new BusinessException(ResultCode.PARAM_ERROR,
                            "提醒偏移必须在 0~" + MAX_REMINDER_OFFSET + " 分钟之间");
                }
            }
        }
    }

    /**
     * 星期几只能配「每周」。
     *
     * <p>不静默忽略：{@code byDay} 配 {@code DAILY} 时，用户本意八成是「每周一三五」，
     * 静默丢掉的结果是每天一次——接口 200、日历完全不是用户说的样子。
     */
    private void requireByDayScope(String freq, List<String> byDay) {
        if (byDay == null || byDay.isEmpty()) {
            return;
        }
        if (!FREQ_WEEKLY.equals(freq)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "byDay（星期几）只能与 freq=WEEKLY 一起用；每天/每月请改用 interval");
        }
    }

    private void applyContent(ScheduleSeries series, SeriesUpdateRequest request) {
        if (request.getTitle() != null) {
            String title = request.getTitle().trim();
            if (title.isEmpty()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "标题不能为空");
            }
            series.setTitle(title);
        }
        if (request.getContent() != null) {
            series.setContent(request.getContent());
        }
        if (request.getLocation() != null) {
            series.setLocation(request.getLocation());
        }
        if (request.getPriority() != null) {
            series.setPriority(request.getPriority());
        }
        if (request.getAllDay() != null) {
            series.setAllDay(request.getAllDay());
        }
        if (request.getTags() != null) {
            series.setTags(request.getTags());
        }
        if (request.getRemindOffsets() != null) {
            // 空列表会变成 null，表示「取消全部提醒」，落库要显式置空
            series.setRemindOffsets(formatOffsets(request.getRemindOffsets()));
        }
    }

    /**
     * 把规则字段写到实体上，返回「规则是否变了」。
     *
     * <p>只改内容不重建实例，是因为内容改动可以批量 UPDATE 完成；
     * 规则改动会让「哪几天有安排」整体变化，只能删了重排。
     */
    private boolean applyRule(ScheduleSeries series, SeriesUpdateRequest request) {
        boolean changed = false;
        if (request.getFreq() != null) {
            series.setFreq(request.getFreq());
            changed = true;
        }
        if (request.getInterval() != null) {
            series.setIntervalVal(request.getInterval());
            changed = true;
        }
        if (request.getByDay() != null) {
            series.setByDay(toByDayCsv(request.getByDay()));
            changed = true;
        }
        if (request.getUntilDate() != null) {
            series.setUntilDate(request.getUntilDate());
            changed = true;
        }
        if (request.getCountLimit() != null) {
            series.setCountLimit(request.getCountLimit());
            changed = true;
        }
        return changed;
    }

    /**
     * 把 byDay / remindOffsets 显式写成 NULL。
     *
     * <p>{@code updateById} 跳过 null 字段，靠它永远清不掉一列——
     * 表现在外面就是「改成每天了，但星期几的残留还在」。
     */
    private void clearSeriesColumns(Long userId, Long seriesId, boolean clearByDay, boolean clearRemind) {
        if (!clearByDay && !clearRemind) {
            return;
        }
        LambdaUpdateWrapper<ScheduleSeries> update = new LambdaUpdateWrapper<ScheduleSeries>()
                .eq(ScheduleSeries::getId, seriesId)
                .eq(ScheduleSeries::getUserId, userId);
        if (clearByDay) {
            update.set(ScheduleSeries::getByDay, null);
        }
        if (clearRemind) {
            update.set(ScheduleSeries::getRemindOffsets, null);
        }
        seriesWrapperService.update(update);
    }

    // ==================== 查询与响应 ====================

    private ScheduleSeries requireOwnedSeries(Long userId, Long seriesId) {
        if (seriesId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "系列ID不能为空");
        }
        ScheduleSeries series = seriesWrapperService.getOne(
                new LambdaQueryWrapper<ScheduleSeries>()
                        .eq(ScheduleSeries::getId, seriesId)
                        .eq(ScheduleSeries::getUserId, userId),
                false);
        if (series == null) {
            // 不区分「不存在」与「不属于你」：区分了等于告诉攻击者哪些 id 真实存在
            throw new BusinessException(ResultCode.NOT_FOUND, "系列不存在或无权访问");
        }
        return series;
    }

    private ScheduleItem requireAnchorItem(Long userId, Long seriesId, Long itemId) {
        if (itemId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "范围是 future 时必须指定锚点实例 anchorItemId（从哪一次起）");
        }
        ScheduleItem item = itemWrapperService.getOne(
                new LambdaQueryWrapper<ScheduleItem>()
                        .eq(ScheduleItem::getId, itemId)
                        .eq(ScheduleItem::getUserId, userId),
                false);
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "锚点事项不存在或无权访问");
        }
        if (!Objects.equals(seriesId, item.getSeriesId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "锚点事项不属于这个系列");
        }
        return item;
    }

    private ScheduleSeries findByIdempotentKey(Long userId, String requestId) {
        return seriesWrapperService.getOne(
                new LambdaQueryWrapper<ScheduleSeries>()
                        .eq(ScheduleSeries::getUserId, userId)
                        .eq(ScheduleSeries::getRequestId, requestId),
                false);
    }

    private SeriesResponse buildResponse(Long userId, ScheduleSeries series) {
        Map<Long, SeriesInstanceStat> stats = statsOf(userId, List.of(series.getId()));
        return toResponse(series, stats.get(series.getId()));
    }

    private Map<Long, SeriesInstanceStat> statsOf(Long userId, List<Long> seriesIds) {
        if (seriesIds == null || seriesIds.isEmpty()) {
            return Map.of();
        }
        return seriesMapper.selectInstanceStats(userId, seriesIds).stream()
                .collect(Collectors.toMap(SeriesInstanceStat::getSeriesId, Function.identity(), (a, b) -> a));
    }

    private SeriesResponse toResponse(ScheduleSeries series, SeriesInstanceStat stat) {
        // byDay 与 remindOffsets 在实体里是逗号串、在响应里是数组，让 BeanUtil 硬转会试着
        // 把 "MO,WE" 拆成一个 List<Character>，所以显式忽略、下面手工set
        SeriesResponse response = BeanUtil.copyProperties(series, SeriesResponse.class,
                "byDay", "remindOffsets");
        response.setInterval(series.getIntervalVal());
        response.setByDay(RecurrenceExpander.toDayList(series.getByDay()));
        response.setRemindOffsets(parseOffsets(series.getRemindOffsets()));
        if (stat != null) {
            response.setItemCount(stat.getItemCount());
            response.setFirstItemId(stat.getFirstItemId());
            response.setLastItemId(stat.getLastItemId());
        }
        return response;
    }

    /**
     * 提醒的基准时刻：日程取开始时间，待办取截止时间。
     */
    private LocalDateTime baseTimeOf(ScheduleItem item) {
        return TYPE_SCHEDULE.equals(item.getItemType()) ? item.getStartTime() : item.getDueTime();
    }

    private String toByDayCsv(List<String> byDay) {
        if (byDay == null || byDay.isEmpty()) {
            return null;
        }
        // 交给 RecurrenceExpander 统一解析与排序，避免这里和展开器各写一套规则
        Set<java.time.DayOfWeek> days = byDay.stream()
                .filter(StringUtils::hasText)
                .map(token -> RecurrenceExpander.parseDay(token.trim().toUpperCase()))
                .collect(Collectors.toSet());
        return RecurrenceExpander.formatByDay(days);
    }

    private List<Integer> parseOffsets(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .map(Integer::valueOf)
                .distinct()
                .sorted()
                .toList();
    }

    private String formatOffsets(List<Integer> offsets) {
        if (offsets == null || offsets.isEmpty()) {
            return null;
        }
        return offsets.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
