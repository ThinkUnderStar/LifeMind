package thinkunderstar.lifemind.lifemindschedule.service.core.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thinkunderstar.lifemind.lifemindschedule.common.ResultCode;
import thinkunderstar.lifemind.lifemindschedule.dto.response.ReminderResponse;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleItem;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleReminder;
import thinkunderstar.lifemind.lifemindschedule.event.NotificationEvent;
import thinkunderstar.lifemind.lifemindschedule.exception.BusinessException;
import thinkunderstar.lifemind.lifemindschedule.mapper.ScheduleReminderMapper;
import thinkunderstar.lifemind.lifemindschedule.service.core.ReminderService;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.ScheduleItemWrapperService;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.ScheduleReminderWrapperService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 提醒服务实现。
 */
@Slf4j
@Service
public class ReminderServiceImpl implements ReminderService {

    /**
     * 默认渠道：站内信。短信 / 邮件留给以后在通知服务侧加渠道，本服务只负责标注意图
     */
    private static final String DEFAULT_CHANNEL = "inbox";

    /**
     * 提醒状态
     */
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_SENT = 1;
    private static final int STATUS_CANCELLED = 2;

    /**
     * 提醒内容的时刻格式：只到分钟，用户不需要秒
     */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ScheduleReminderMapper reminderMapper;
    private final ScheduleReminderWrapperService reminderWrapperService;
    private final ScheduleItemWrapperService itemWrapperService;
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${life-mind.schedule.reminder.enabled:true}")
    private boolean reminderEnabled;

    @Value("${life-mind.schedule.reminder.batch-size:100}")
    private int batchSize;

    @Value("${life-mind.schedule.reminder.max-retry:3}")
    private int maxRetry;

    @Value("${life-mind.schedule.reminder.notification-topic:life-mind-notification}")
    private String notificationTopic;

    @Value("${life-mind.schedule.reminder.notification-tag:REMINDER}")
    private String notificationTag;

    public ReminderServiceImpl(ScheduleReminderMapper reminderMapper,
                               ScheduleReminderWrapperService reminderWrapperService,
                               ScheduleItemWrapperService itemWrapperService,
                               RocketMQTemplate rocketMQTemplate) {
        this.reminderMapper = reminderMapper;
        this.reminderWrapperService = reminderWrapperService;
        this.itemWrapperService = itemWrapperService;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuildForItem(Long userId, ScheduleItem item, List<Integer> offsets) {
        if (item == null || item.getId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "事项信息不完整，无法设置提醒");
        }

        // 先全部取消：重算是「替换」语义。保留旧行会造出两套时间对不上的提醒，
        // 而且旧行可能已经到点，扫描时立刻发一条错误的
        reminderMapper.cancelPendingByItem(item.getId(), userId);

        LocalDateTime baseTime = resolveBaseTime(item);
        if (baseTime == null) {
            // 走到这里说明调用方没拦住「设了提醒但没有时间」的请求。接口层已经会直接报错，
            // 这里是最后一道防线：不建提醒、但必须留下 warn 日志，否则表现就是
            // 「接口返回成功、提醒一条没有」，从 debug 日志里根本查不出来
            log.warn("事项没有可用基准时间，提醒未创建：itemId={} type={} offsets={}",
                    item.getId(), item.getItemType(), offsets);
            return;
        }
        if (offsets == null || offsets.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<ScheduleReminder> rows = offsets.stream()
                .filter(Objects::nonNull)
                // 去重后排序：同一偏移配两次没有意义，排序让插入顺序稳定、便于排查
                .distinct()
                .sorted()
                .map(offset -> baseTime.minusMinutes(offset))
                // 已经过去的时刻不建：建了会在下一次扫描被立刻捞出来，
                // 变成「刚建完事项就收到提醒」的错误体验
                .filter(remindTime -> remindTime.isAfter(now))
                .map(remindTime -> buildReminder(userId, item.getId(), remindTime,
                        (int) java.time.Duration.between(remindTime, baseTime).toMinutes()))
                .toList();

        if (rows.isEmpty()) {
            log.debug("提醒时刻都在过去，未创建：itemId={} baseTime={}", item.getId(), baseTime);
            return;
        }

        reminderWrapperService.saveBatch(rows);
        log.info("提醒已重建：itemId={} 条数={} baseTime={}", item.getId(), rows.size(), baseTime);
    }

    @Override
    public void cancelPending(Long userId, Long itemId) {
        int cancelled = reminderMapper.cancelPendingByItem(itemId, userId);
        if (cancelled > 0) {
            log.info("已取消待发送提醒：itemId={} 条数={}", itemId, cancelled);
        }
    }

    @Override
    public List<ReminderResponse> listByItem(Long userId, Long itemId) {
        List<ScheduleReminder> reminders = reminderWrapperService.list(
                new LambdaQueryWrapper<ScheduleReminder>()
                        .eq(ScheduleReminder::getUserId, userId)
                        .eq(ScheduleReminder::getItemId, itemId)
                        .orderByAsc(ScheduleReminder::getRemindTime));

        return reminders.stream()
                .map(reminder -> BeanUtil.copyProperties(reminder, ReminderResponse.class))
                .toList();
    }

    @Override
    public List<ReminderResponse> upcoming(Long userId, int days) {
        // 压到 1~30 天：0 天没意义，超过 30 天的列表用户也不会看
        int safeDays = Math.max(1, Math.min(days, 30));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = now.plusDays(safeDays);

        List<ScheduleReminder> reminders = reminderWrapperService.list(
                new LambdaQueryWrapper<ScheduleReminder>()
                        .eq(ScheduleReminder::getUserId, userId)
                        .eq(ScheduleReminder::getStatus, STATUS_PENDING)
                        .ge(ScheduleReminder::getRemindTime, now)
                        .lt(ScheduleReminder::getRemindTime, end)
                        .orderByAsc(ScheduleReminder::getRemindTime)
                        .last("LIMIT 100"));

        return reminders.stream()
                .map(reminder -> BeanUtil.copyProperties(reminder, ReminderResponse.class))
                .toList();
    }

    @Override
    public int dispatchDueReminders() {
        if (!reminderEnabled) {
            return 0;
        }

        List<ScheduleReminder> due = reminderMapper.selectDueReminders(batchSize);
        if (due.isEmpty()) {
            return 0;
        }

        Map<Long, ScheduleItem> itemMap = loadItems(due);
        int sent = 0;

        for (ScheduleReminder reminder : due) {
            // 并发闸门：只有把 status 从 0 改成 1 成功的那一次才发消息。
            // 多实例部署时这里会天然去重，不需要分布式锁
            if (reminderMapper.claim(reminder.getId()) == 0) {
                continue;
            }

            ScheduleItem item = itemMap.get(reminder.getItemId());
            if (item == null) {
                // 事项已被删除（逻辑删除后查不到）：这条提醒不该再发，直接取消
                markCancelled(reminder.getId());
                log.info("事项已删除，取消提醒：reminderId={} itemId={}", reminder.getId(), reminder.getItemId());
                continue;
            }

            try {
                rocketMQTemplate.convertAndSend(
                        notificationTopic + ":" + notificationTag,
                        buildEvent(reminder, item));
                sent++;
                log.info("提醒已投递：reminderId={} itemId={} userId={}",
                        reminder.getId(), reminder.getItemId(), reminder.getUserId());
            } catch (Exception e) {
                // 投递失败不抛：一条失败不该打断整批，其余提醒照常发。
                // 回退状态后由下一轮扫描重试，超过上限置为发送失败
                log.warn("提醒投递失败：reminderId={} itemId={}", reminder.getId(), reminder.getItemId(), e);
                reminderMapper.releaseOnFailure(reminder.getId(), maxRetry);
            }
        }

        return sent;
    }

    /**
     * 算提醒的基准时刻：日程看开始时间，待办看截止时间。
     *
     * <p>两者都没有时返回 null——算不出触发时刻就不该建提醒。
     */
    private LocalDateTime resolveBaseTime(ScheduleItem item) {
        if ("schedule".equals(item.getItemType())) {
            return item.getStartTime();
        }
        if ("todo".equals(item.getItemType())) {
            return item.getDueTime();
        }
        return null;
    }

    private ScheduleReminder buildReminder(Long userId, Long itemId, LocalDateTime remindTime, int offsetMinutes) {
        ScheduleReminder reminder = new ScheduleReminder();
        reminder.setUserId(userId);
        reminder.setItemId(itemId);
        reminder.setRemindTime(remindTime);
        reminder.setOffsetMinutes(offsetMinutes);
        reminder.setChannel(DEFAULT_CHANNEL);
        reminder.setStatus(STATUS_PENDING);
        reminder.setRetryCount(0);
        return reminder;
    }

    /**
     * 批量取事项：扫描时逐条查会变成 N 次数据库往返。
     */
    private Map<Long, ScheduleItem> loadItems(List<ScheduleReminder> reminders) {
        List<Long> itemIds = reminders.stream()
                .map(ScheduleReminder::getItemId)
                .distinct()
                .toList();
        // listByIds 受 @TableLogic 约束，已删除的事项本来就不会出现
        return itemWrapperService.listByIds(itemIds).stream()
                .collect(Collectors.toMap(ScheduleItem::getId, Function.identity(), (a, b) -> a));
    }

    private void markCancelled(Long reminderId) {
        reminderWrapperService.update(new LambdaUpdateWrapper<ScheduleReminder>()
                .eq(ScheduleReminder::getId, reminderId)
                .eq(ScheduleReminder::getStatus, STATUS_SENT)
                .set(ScheduleReminder::getStatus, STATUS_CANCELLED)
                .set(ScheduleReminder::getSentTime, null));
    }

    private NotificationEvent buildEvent(ScheduleReminder reminder, ScheduleItem item) {
        boolean schedule = "schedule".equals(item.getItemType());

        NotificationEvent event = new NotificationEvent();
        event.setUserId(reminder.getUserId());
        // type 用 schedule 而不是 itemType：通知服务按 type 分类展示，
        // 「待办」在用户眼里也是日程服务的一部分
        event.setType("schedule");
        event.setTitle(schedule ? "日程提醒" : "待办提醒");
        event.setContent(buildContent(item, schedule, reminder.getRemindTime()));
        // 系统通知没有发送者
        event.setSenderId(null);
        event.setBizType("schedule_item");
        event.setBizId(String.valueOf(item.getId()));
        event.setJumpUrl(null);
        // 高优先级事项按「重要」发；普通与低优先级按普通，避免通知栏全是红点
        event.setLevel(item.getPriority() != null && item.getPriority() == 2 ? 1 : 0);
        // 提醒恒弹窗，且不从 level 推导：level 区分「多要紧」，而所有提醒都必须
        // 打断用户——用户设了「到点提醒」，到点只往通知箱塞一条就等于没提醒
        event.setNeedPopup(1);
        // 扩展信息只放前端跳转需要的类型，不放标题等内容（正文已经带了）
        event.setExtra("{\"itemType\":\"" + item.getItemType() + "\"}");
        return event;
    }

    private String buildContent(ScheduleItem item, boolean schedule, LocalDateTime remindTime) {
        // 提醒发出去的这一刻就是用户该行动的时候，所以文案写「现在」而不是复述提前量
        String title = item.getTitle() == null ? "未命名事项" : item.getTitle();
        if (schedule) {
            return String.format("「%s」即将开始（%s）", title, remindTime.format(TIME_FORMATTER));
        }
        return String.format("「%s」即将到期（%s）", title, remindTime.format(TIME_FORMATTER));
    }
}
