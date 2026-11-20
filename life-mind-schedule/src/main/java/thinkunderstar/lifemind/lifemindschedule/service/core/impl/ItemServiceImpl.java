package thinkunderstar.lifemind.lifemindschedule.service.core.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.common.ResultCode;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemBatchCompleteRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemBatchDeleteRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemCancelRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemCompleteRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemCreateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemQueryRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemUpdateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.response.BatchResultResponse;
import thinkunderstar.lifemind.lifemindschedule.dto.response.ItemResponse;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleItem;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleReminder;
import thinkunderstar.lifemind.lifemindschedule.exception.BusinessException;
import thinkunderstar.lifemind.lifemindschedule.service.core.ItemService;
import thinkunderstar.lifemind.lifemindschedule.service.core.ReminderService;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.ScheduleItemWrapperService;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.ScheduleReminderWrapperService;
import thinkunderstar.lifemind.lifemindschedule.util.UserContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 事项服务实现。
 */
@Slf4j
@Service
public class ItemServiceImpl implements ItemService {

    /**
     * 事项类型
     */
    private static final String TYPE_SCHEDULE = "schedule";
    private static final String TYPE_TODO = "todo";

    /**
     * 事项状态
     */
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_CANCELLED = 2;

    /**
     * 提醒状态：待发送
     */
    private static final int REMINDER_PENDING = 0;

    /**
     * 默认值：中优先级、非全天
     */
    private static final int DEFAULT_PRIORITY = 1;
    private static final int DEFAULT_ALL_DAY = 0;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 批量接口一次最多处理多少条：与 DTO 上的 {@code @Size(max = 100)} 保持一致，
     * 这里再判一次是因为按 id 批量取详情走的是查询串，没有 DTO 校验兜着
     */
    private static final int MAX_BATCH_SIZE = 100;

    /**
     * 允许清空的字段。
     *
     * <p>标题和开始时间不在里面：标题必填，开始时间是日程的存在前提，
     * 清空它们只会造出脏数据，想这么做得删了重建。
     */
    private static final Set<String> CLEARABLE_FIELDS = Set.of("content", "location", "endTime", "dueTime", "tags");

    private final ScheduleItemWrapperService itemWrapperService;
    private final ScheduleReminderWrapperService reminderWrapperService;
    private final ReminderService reminderService;

    public ItemServiceImpl(ScheduleItemWrapperService itemWrapperService,
                           ScheduleReminderWrapperService reminderWrapperService,
                           ReminderService reminderService) {
        this.itemWrapperService = itemWrapperService;
        this.reminderWrapperService = reminderWrapperService;
        this.reminderService = reminderService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<ItemResponse> create(ItemCreateRequest request) {
        Long userId = UserContext.currentUserId();

        String requestId = trimToNull(request.getRequestId());
        if (requestId != null) {
            ScheduleItem existing = findByIdempotentKey(userId, requestId);
            if (existing != null) {
                // 幂等命中：不新建、不改动，直接把上次的结果还回去。
                // agent 重试时看到的就是「已经建好了」，不会再建第二条
                log.info("创建幂等命中：userId={} requestId={} itemId={}", userId, requestId, existing.getId());
                return Result.success(buildDetail(userId, existing));
            }
        }

        ScheduleItem item = new ScheduleItem();
        BeanUtil.copyProperties(request, item);
        item.setUserId(userId);
        item.setStatus(STATUS_PENDING);
        item.setAllDay(item.getAllDay() == null ? DEFAULT_ALL_DAY : item.getAllDay());
        item.setPriority(item.getPriority() == null ? DEFAULT_PRIORITY : item.getPriority());
        item.setRequestId(requestId);
        item.setSource(UserContext.currentClient());

        normalizeByType(item);
        validateTime(item);
        requireReminderFeasible(item, request.getRemindOffsets());

        try {
            itemWrapperService.save(item);
        } catch (DuplicateKeyException e) {
            // 并发下两个相同 requestId 同时通过了上面的预检查，唯一索引在这里兜底
            ScheduleItem existing = requestId == null ? null : findByIdempotentKey(userId, requestId);
            if (existing != null) {
                log.info("创建并发幂等命中：userId={} requestId={} itemId={}", userId, requestId, existing.getId());
                return Result.success(buildDetail(userId, existing));
            }
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "requestId 已被占用（对应事项可能已被删除），请换一个 requestId 重试");
        }

        // 有偏移才建提醒；null 与空列表都表示「不提醒」——创建时没有「保留原设置」这回事
        if (request.getRemindOffsets() != null && !request.getRemindOffsets().isEmpty()) {
            reminderService.rebuildForItem(userId, item, request.getRemindOffsets());
        }

        log.info("事项已创建：id={} userId={} type={} source={}",
                item.getId(), userId, item.getItemType(), item.getSource());
        return Result.success(buildDetail(userId, item));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<ItemResponse> update(ItemUpdateRequest request) {
        Long userId = UserContext.currentUserId();
        ScheduleItem item = requireOwned(userId, request.getId());

        // 改时间前先记下原基准时刻：只有它变了才需要按原偏移重算提醒
        LocalDateTime oldBaseTime = baseTimeOf(item);

        applyExplicitFields(item, request);

        Set<String> cleared = normalizeClearFields(request.getClearFields());
        applyClearsInMemory(item, cleared);

        validateTime(item);
        itemWrapperService.updateById(item);
        // updateById 会跳过 null 字段（那是「不改」的语义），
        // 所以清空必须单独用 UpdateWrapper 显式 set null
        clearColumnsInDb(userId, item.getId(), cleared);

        boolean timeChanged = !Objects.equals(oldBaseTime, baseTimeOf(item));

        // 三态：显式传空列表 = 取消提醒；显式传偏移 = 按新偏移重建；
        // 不传且时间变了 = 保留原偏移重算时刻（否则改完时间，提醒还停在旧时刻）
        List<Integer> effectiveOffsets = request.getRemindOffsets();
        if (effectiveOffsets != null) {
            requireReminderFeasible(item, effectiveOffsets);
        } else if (timeChanged) {
            List<Integer> pending = pendingOffsets(userId, item.getId());
            if (!pending.isEmpty()) {
                if (baseTimeOf(item) == null) {
                    // 时间被清掉了，算不出提醒时刻。留着旧提醒会在错误的时间响
                    reminderService.cancelPending(userId, item.getId());
                    log.info("事项时间已清空，取消待发送提醒：itemId={}", item.getId());
                } else {
                    effectiveOffsets = pending;
                }
            }
        }

        if (effectiveOffsets != null) {
            reminderService.rebuildForItem(userId, item, effectiveOffsets);
        }

        log.info("事项已更新：id={} userId={} timeChanged={} cleared={}",
                item.getId(), userId, timeChanged, cleared);
        return Result.success(buildDetail(userId, item));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(Long itemId) {
        Long userId = UserContext.currentUserId();
        ScheduleItem item = requireOwned(userId, itemId);
        doDelete(userId, item.getId());
        log.info("事项已删除：id={} userId={}", itemId, userId);
        return Result.success();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> cancel(ItemCancelRequest request) {
        Long userId = UserContext.currentUserId();
        ScheduleItem item = requireOwned(userId, request.getId());

        // 重复取消是幂等操作：已经是已取消状态就不再动 update_time
        if (item.getStatus() != null && item.getStatus() == STATUS_CANCELLED) {
            return Result.success();
        }

        item.setStatus(STATUS_CANCELLED);
        // 取消不是完成，completed_time 保持原样（可能是 null，也可能是之前真做完过又取消）
        itemWrapperService.updateById(item);
        // 会都不开了，就不该再弹提醒
        reminderService.cancelPending(userId, item.getId());

        log.info("事项已取消：id={} userId={}", item.getId(), userId);
        return Result.success();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> complete(ItemCompleteRequest request) {
        Long userId = UserContext.currentUserId();
        ScheduleItem item = requireOwned(userId, request.getId());
        doComplete(userId, item, Boolean.TRUE.equals(request.getCompleted()));
        log.info("事项完成状态已变更：id={} completed={}", item.getId(), request.getCompleted());
        return Result.success();
    }

    @Override
    public Result<BatchResultResponse> batchComplete(ItemBatchCompleteRequest request) {
        Long userId = UserContext.currentUserId();
        BatchResultResponse result = new BatchResultResponse();

        // 刻意不加 @Transactional：一条失败不该把已经成功的回滚掉，
        // 那样用户重试时会把做过的再做一遍
        for (Long id : request.getIds()) {
            try {
                ScheduleItem item = requireOwned(userId, id);
                doComplete(userId, item, Boolean.TRUE.equals(request.getCompleted()));
                result.addSuccess(id);
            } catch (BusinessException e) {
                result.addFailure(id, e.getMessage());
            } catch (Exception e) {
                log.error("批量标记完成失败：id={}", id, e);
                result.addFailure(id, "系统异常");
            }
        }

        log.info("批量完成状态变更：userId={} completed={} 成功={} 失败={}",
                userId, request.getCompleted(), result.getSuccessCount(), result.getFailed().size());
        return Result.success(result);
    }

    @Override
    public Result<BatchResultResponse> batchDelete(ItemBatchDeleteRequest request) {
        Long userId = UserContext.currentUserId();
        BatchResultResponse result = new BatchResultResponse();

        for (Long id : request.getIds()) {
            try {
                ScheduleItem item = requireOwned(userId, id);
                doDelete(userId, item.getId());
                result.addSuccess(id);
            } catch (BusinessException e) {
                result.addFailure(id, e.getMessage());
            } catch (Exception e) {
                log.error("批量删除失败：id={}", id, e);
                result.addFailure(id, "系统异常");
            }
        }

        log.info("批量删除：userId={} 成功={} 失败={}",
                userId, result.getSuccessCount(), result.getFailed().size());
        return Result.success(result);
    }

    @Override
    public Result<ItemResponse> detail(Long itemId) {
        Long userId = UserContext.currentUserId();
        ScheduleItem item = requireOwned(userId, itemId);
        return Result.success(buildDetail(userId, item));
    }

    @Override
    public Result<List<ItemResponse>> listByIds(List<Long> ids) {
        Long userId = UserContext.currentUserId();

        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "事项ID列表不能为空");
        }
        // 去重：调用方常常把上一轮的分页结果和新增的 id 混在一起传
        List<Long> distinctIds = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "事项ID列表不能为空");
        }
        if (distinctIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "一次最多查询 " + MAX_BATCH_SIZE + " 条");
        }

        List<ScheduleItem> items = itemWrapperService.list(
                new LambdaQueryWrapper<ScheduleItem>()
                        .eq(ScheduleItem::getUserId, userId)
                        .in(ScheduleItem::getId, distinctIds));

        // 按入参顺序返回：调用方拿到的顺序与它请求的顺序一致，才好逐个对应
        Map<Long, ScheduleItem> byId = items.stream()
                .collect(Collectors.toMap(ScheduleItem::getId, Function.identity(), (a, b) -> a));
        List<ItemResponse> responses = distinctIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(this::toResponse)
                .toList();

        return Result.success(responses);
    }

    @Override
    public Result<IPage<ItemResponse>> page(ItemQueryRequest queryRequest) {
        Long userId = UserContext.currentUserId();

        long pageNum = queryRequest.getPageNum() == null || queryRequest.getPageNum() <= 0
                ? 1 : queryRequest.getPageNum();
        long pageSize = queryRequest.getPageSize() == null || queryRequest.getPageSize() <= 0
                ? DEFAULT_PAGE_SIZE : Math.min(queryRequest.getPageSize(), MAX_PAGE_SIZE);

        LambdaQueryWrapper<ScheduleItem> wrapper = new LambdaQueryWrapper<ScheduleItem>()
                .eq(ScheduleItem::getUserId, userId);

        if (Boolean.TRUE.equals(queryRequest.getOverdue())) {
            // 逾期已经限定了「待办 + 未完成 + 有截止时间 + 已过点」，
            // 再叠加 itemType/status 只会让调用方困惑，所以这里的条件直接覆盖它们
            wrapper.eq(ScheduleItem::getItemType, TYPE_TODO)
                    .eq(ScheduleItem::getStatus, STATUS_PENDING)
                    .isNotNull(ScheduleItem::getDueTime)
                    .lt(ScheduleItem::getDueTime, LocalDateTime.now());
        } else {
            wrapper.eq(StringUtils.hasText(queryRequest.getItemType()),
                            ScheduleItem::getItemType, queryRequest.getItemType())
                    .eq(queryRequest.getStatus() != null,
                            ScheduleItem::getStatus, queryRequest.getStatus());
        }

        String keyword = queryRequest.getKeyword();
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            wrapper.and(q -> q.like(ScheduleItem::getTitle, trimmed)
                    .or(w -> w.like(ScheduleItem::getContent, trimmed)));
        }

        applyTimeFilter(wrapper, queryRequest);

        // 混合排序：日程看开始时间、待办看截止时间，统一用 COALESCE 排。
        // 两者都没有的待办排最后（MySQL 升/降序里 NULL 都在末尾），符合「没安排的事往后放」
        wrapper.last("ORDER BY COALESCE(start_time, due_time) DESC, id DESC");

        Page<ScheduleItem> page = itemWrapperService.page(new Page<>(pageNum, pageSize), wrapper);
        IPage<ItemResponse> result = page.convert(this::toResponse);
        return Result.success(result);
    }

    /**
     * 把请求里显式给出的字段写到实体上。
     *
     * <p>单独抽出来是为了让 {@link #update} 的主流程一眼能看出顺序：
     * 先赋值、再清空、最后校验。
     */
    private void applyExplicitFields(ScheduleItem item, ItemUpdateRequest request) {
        if (request.getTitle() != null) {
            String title = request.getTitle().trim();
            if (title.isEmpty()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "标题不能为空");
            }
            item.setTitle(title);
        }
        if (request.getContent() != null) {
            item.setContent(request.getContent());
        }
        if (request.getLocation() != null) {
            item.setLocation(request.getLocation());
        }
        if (request.getStartTime() != null) {
            item.setStartTime(request.getStartTime());
        }
        if (request.getEndTime() != null) {
            item.setEndTime(request.getEndTime());
        }
        if (request.getDueTime() != null) {
            item.setDueTime(request.getDueTime());
        }
        if (request.getAllDay() != null) {
            item.setAllDay(request.getAllDay());
        }
        if (request.getPriority() != null) {
            item.setPriority(request.getPriority());
        }
        if (request.getTags() != null) {
            item.setTags(request.getTags());
        }
    }

    /**
     * 校验并归一化 clearFields。
     *
     * <p>不认识的字段名直接报错而不是忽略：调用方拼错一个字段名（比如写成 {@code due_time}）
     * 却拿到 200，会以为清空成功了，实际数据没变——这种静默失败比报错难查得多。
     */
    private Set<String> normalizeClearFields(List<String> clearFields) {
        if (clearFields == null || clearFields.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = clearFields.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toSet());

        List<String> unknown = normalized.stream()
                .filter(field -> !CLEARABLE_FIELDS.contains(field))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "不支持清空的字段：" + String.join(",", unknown)
                            + "；可选值：content,location,endTime,dueTime,tags");
        }
        return normalized;
    }

    /**
     * 在内存对象上清空字段，供后续校验与响应使用。
     */
    private void applyClearsInMemory(ScheduleItem item, Set<String> cleared) {
        if (cleared.isEmpty()) {
            return;
        }
        if (cleared.contains("content")) {
            item.setContent(null);
        }
        if (cleared.contains("location")) {
            item.setLocation(null);
        }
        if (cleared.contains("endTime")) {
            item.setEndTime(null);
        }
        if (cleared.contains("dueTime")) {
            item.setDueTime(null);
        }
        if (cleared.contains("tags")) {
            item.setTags(null);
        }
    }

    /**
     * 把要清空的列真正写成 NULL。
     *
     * <p>必须单独走一次 {@code update(wrapper)}：MyBatis-Plus 的 {@code updateById}
     * 默认跳过 null 字段，靠它永远清不掉一列。
     */
    private void clearColumnsInDb(Long userId, Long itemId, Set<String> cleared) {
        if (cleared.isEmpty()) {
            return;
        }
        LambdaUpdateWrapper<ScheduleItem> update = new LambdaUpdateWrapper<ScheduleItem>()
                .eq(ScheduleItem::getId, itemId)
                .eq(ScheduleItem::getUserId, userId);

        boolean any = false;
        for (String field : cleared) {
            switch (field) {
                case "content" -> {
                    update.set(ScheduleItem::getContent, null);
                    any = true;
                }
                case "location" -> {
                    update.set(ScheduleItem::getLocation, null);
                    any = true;
                }
                case "endTime" -> {
                    update.set(ScheduleItem::getEndTime, null);
                    any = true;
                }
                case "dueTime" -> {
                    update.set(ScheduleItem::getDueTime, null);
                    any = true;
                }
                case "tags" -> {
                    update.set(ScheduleItem::getTags, null);
                    any = true;
                }
                default -> {
                    // normalizeClearFields 已经拦掉了未知字段，这里不会走到
                }
            }
        }
        if (any) {
            itemWrapperService.update(update);
        }
    }

    /**
     * 删除的实现：先取消提醒再删事项。
     *
     * <p>反过来的话，删除成功但取消失败会留下一条指向已删事项的待发送提醒，
     * 扫描时只能靠「查不到事项」兜底。
     */
    private void doDelete(Long userId, Long itemId) {
        reminderService.cancelPending(userId, itemId);
        itemWrapperService.removeById(itemId);
    }

    /**
     * 完成状态变更的实现。
     *
     * @param completed true-标记完成 false-取消完成（顺带把「已取消」恢复成未完成）
     */
    private void doComplete(Long userId, ScheduleItem item, boolean completed) {
        if (completed) {
            // 重复点完成是幂等操作，但已完成的不要刷新完成时间
            if (item.getStatus() == null || item.getStatus() != STATUS_DONE) {
                item.setStatus(STATUS_DONE);
                item.setCompletedTime(LocalDateTime.now());
                itemWrapperService.updateById(item);
            }
            // 事情做完了还弹提醒是最招骂的体验，未发送的提醒一律取消
            reminderService.cancelPending(userId, item.getId());
            return;
        }

        item.setStatus(STATUS_PENDING);
        item.setCompletedTime(null);
        itemWrapperService.updateById(item);
        // 刻意不恢复已取消的提醒：分不清用户是「手滑取消勾选」还是「重新开始做」，
        // 擅自恢复会在用户没预期的时候弹提醒。要重新提醒请重新保存提醒设置
    }

    /**
     * 时间过滤：单日优先，其次区间。
     *
     * <p>用「日程看 start_time、待办看 due_time」的 OR 组合，
     * 而不是统一用一个字段——待办的 start_time 恒为 null，用它过滤会永远查不到。
     */
    private void applyTimeFilter(LambdaQueryWrapper<ScheduleItem> wrapper, ItemQueryRequest queryRequest) {
        LocalDate date = queryRequest.getDate();
        if (date != null) {
            LocalDateTime start = date.atStartOfDay();
            appendRange(wrapper, start, start.plusDays(1));
            return;
        }

        LocalDate startDate = queryRequest.getStartDate();
        LocalDate endDate = queryRequest.getEndDate();
        if (startDate == null && endDate == null) {
            return;
        }

        // 只传一端时，另一端补成同一天，避免「只给结束日期就查出全部历史」这种意外
        LocalDate effectiveStart = startDate != null ? startDate : endDate;
        LocalDate effectiveEnd = endDate != null ? endDate : startDate;
        if (effectiveEnd.isBefore(effectiveStart)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }

        appendRange(wrapper, effectiveStart.atStartOfDay(), effectiveEnd.plusDays(1).atStartOfDay());
    }

    private void appendRange(LambdaQueryWrapper<ScheduleItem> wrapper,
                             LocalDateTime start,
                             LocalDateTime end) {
        wrapper.and(outer -> outer
                .and(s -> s.eq(ScheduleItem::getItemType, TYPE_SCHEDULE)
                        .ge(ScheduleItem::getStartTime, start)
                        .lt(ScheduleItem::getStartTime, end))
                .or(t -> t.eq(ScheduleItem::getItemType, TYPE_TODO)
                        .ge(ScheduleItem::getDueTime, start)
                        .lt(ScheduleItem::getDueTime, end)));
    }

    /**
     * 按幂等键取已存在的事项。
     *
     * <p>用 {@code getOne(wrapper, false)}：唯一索引保证了最多一条，
     * 但这里仍然不让它抛异常——幂等场景下「查不到」是正常路径。
     */
    private ScheduleItem findByIdempotentKey(Long userId, String requestId) {
        return itemWrapperService.getOne(
                new LambdaQueryWrapper<ScheduleItem>()
                        .eq(ScheduleItem::getUserId, userId)
                        .eq(ScheduleItem::getRequestId, requestId),
                false);
    }

    /**
     * 按 id + userId 取事项，取不到直接 404。
     *
     * <p>userId 放进查询条件而不是查出来再比对：少一次判断，
     * 也不会出现「先查后判」之间被改掉归属的情况。
     */
    private ScheduleItem requireOwned(Long userId, Long itemId) {
        if (itemId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "事项ID不能为空");
        }
        ScheduleItem item = itemWrapperService.getOne(
                new LambdaQueryWrapper<ScheduleItem>()
                        .eq(ScheduleItem::getId, itemId)
                        .eq(ScheduleItem::getUserId, userId),
                false);
        if (item == null) {
            // 不区分「不存在」与「不属于你」：区分了等于告诉攻击者哪些 id 真实存在
            throw new BusinessException(ResultCode.NOT_FOUND, "事项不存在或无权访问");
        }
        return item;
    }

    /**
     * 按类型清理不属于自己的时间字段。
     *
     * <p>待办传了 start_time、日程传了 due_time 都直接置空，而不是报错：
     * 调用方（尤其是 agent）常常统一传一组时间字段，
     * 为这种无害的多余参数拒绝整个请求，体验很差。
     */
    private void normalizeByType(ScheduleItem item) {
        if (TYPE_TODO.equals(item.getItemType())) {
            item.setStartTime(null);
            item.setEndTime(null);
            item.setAllDay(DEFAULT_ALL_DAY);
        } else {
            item.setDueTime(null);
        }
    }

    /**
     * 时间规则校验。
     *
     * <p>日程必须有开始时间：没有开始时间的日程画不到时间轴上，也没法算提醒。
     * 待办不要求截止时间：很多事就是「有空再做」。
     */
    private void validateTime(ScheduleItem item) {
        if (TYPE_SCHEDULE.equals(item.getItemType()) && item.getStartTime() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "日程必须有开始时间");
        }
        if (item.getEndTime() != null) {
            if (item.getStartTime() == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "设置了结束时间就必须有开始时间");
            }
            if (!item.getEndTime().isAfter(item.getStartTime())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "结束时间必须晚于开始时间");
            }
        }
    }

    /**
     * 要求「设了提醒就必须有可提醒的时间」。
     *
     * <p>之前这里什么都不做，结果是：传了提醒偏移但没有基准时间时，
     * {@code rebuildForItem} 在内部静默 return，接口照样返回 200，
     * 返回体里的 {@code remindOffsets} 却是空数组。调用方（尤其 agent）
     * 会以为提醒设好了，用户却永远收不到——这类「返回成功但没生效」最难查。
     *
     * <p>所以改成显式报错：要么补时间，要么别设提醒，别留一个悬空的意图。
     *
     * @param offsets 请求里显式给出的提醒偏移；null 或空列表不校验
     */
    private void requireReminderFeasible(ScheduleItem item, List<Integer> offsets) {
        if (offsets == null || offsets.isEmpty()) {
            return;
        }
        if (baseTimeOf(item) == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "设置了提醒但没有可提醒的时间：日程需要开始时间，待办需要截止时间");
        }
    }

    /**
     * 提醒的基准时刻：日程取开始时间，待办取截止时间。
     */
    private LocalDateTime baseTimeOf(ScheduleItem item) {
        return TYPE_SCHEDULE.equals(item.getItemType()) ? item.getStartTime() : item.getDueTime();
    }

    /**
     * 该事项尚未发送的提醒偏移。用于「改了时间但没改提醒设置」时按原偏移重算。
     */
    private List<Integer> pendingOffsets(Long userId, Long itemId) {
        return reminderWrapperService.list(
                        new LambdaQueryWrapper<ScheduleReminder>()
                                .select(ScheduleReminder::getOffsetMinutes)
                                .eq(ScheduleReminder::getUserId, userId)
                                .eq(ScheduleReminder::getItemId, itemId)
                                .eq(ScheduleReminder::getStatus, REMINDER_PENDING))
                .stream()
                .map(ScheduleReminder::getOffsetMinutes)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ItemResponse buildDetail(Long userId, ScheduleItem item) {
        ItemResponse response = toResponse(item);
        response.setRemindOffsets(pendingOffsets(userId, item.getId()));
        return response;
    }

    private ItemResponse toResponse(ScheduleItem item) {
        return BeanUtil.copyProperties(item, ItemResponse.class);
    }
}
