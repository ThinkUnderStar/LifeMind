package thinkunderstar.lifemind.lifemindschedule.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import thinkunderstar.lifemind.lifemindschedule.service.core.ReminderService;

/**
 * 提醒扫描任务。
 *
 * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：固定间隔在高负载时会把任务堆积，
 * 而「上一次跑完再等 60 秒」才是扫描任务想要的行为。
 *
 * <p>异常必须自己吞掉并记日志：
 * {@code @Scheduled} 方法抛出的异常会打到调度器日志里，虽然不会停止后续调度，
 * 但会掩盖「到底是数据库连不上还是 MQ 挂了」这类关键信息。
 *
 * <p>多实例部署说明：本任务在每个实例上都会跑，靠 {@code ScheduleReminderMapper#claim}
 * 的条件更新去重（只有把 status 从 0 改成 1 成功的实例才发消息），
 * 不需要额外的分布式锁。代价是每个实例都会扫一次库，量级很小可以接受。
 */
@Slf4j
@Component
public class ReminderScanTask {

    private final ReminderService reminderService;

    public ReminderScanTask(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /**
     * 每分钟扫描一次到点提醒并投递到通知服务。
     *
     * <p>间隔与批量大小都在 Nacos 的 schedule.yaml 里，改配置不用重启失败——
     * 需要重启才会生效（@Value 只在启动时注入）。
     */
    @Scheduled(fixedDelayString = "${life-mind.schedule.reminder.scan-interval-ms:60000}")
    public void scan() {
        try {
            int sent = reminderService.dispatchDueReminders();
            if (sent > 0) {
                log.info("提醒扫描完成，本次投递 {} 条", sent);
            }
        } catch (Exception e) {
            // 不吞异常的话，调度器日志里只有堆栈没有上下文，排查时不知道是哪一步挂的
            log.error("提醒扫描失败，等待下一轮重试", e);
        }
    }
}
