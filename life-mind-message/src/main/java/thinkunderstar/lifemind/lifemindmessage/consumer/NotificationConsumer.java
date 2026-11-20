package thinkunderstar.lifemind.lifemindmessage.consumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import thinkunderstar.lifemind.lifemindmessage.event.NotificationEvent;
import thinkunderstar.lifemind.lifemindmessage.service.core.NotificationService;

/**
 * 通知消息消费者。
 *
 * <p>监听 {@code life-mind-notification} Topic 下的所有事件，落库到通知表。
 * 当前只有站内信一种形态：写一行 notification，由前端轮询拉取。
 * 短信 / 邮件 / 推送等外部通道尚未接入——这里没有按渠道分发的 switch，
 * 别照注释去找。
 *
 * <p>消费规范：
 * <ul>
 *     <li>消费组：{@code life-mind-notification-notify-consumer}，全局唯一</li>
 *     <li>Topic：{@code life-mind-notification}</li>
 *     <li>不指定 Tag，消费该 Topic 下所有事件，由消息体中的 {@code type} 字段区分</li>
 * </ul>
 *
 * <p>幂等：MQ 保证"至少投递一次"，重复消息由 {@code NotificationService#sendNotification}
 * 按消息体里的 {@code eventId} 去重——先查一次走快速路径，落库时再由
 * {@code notification.event_id} 上的唯一索引兜住并发窗口。
 *
 * <p>幂等键由生产方生成，粒度是「哪一次业务事件」而非「哪个业务对象」：
 * 日程提醒用 {@code schedule_reminder_{reminderId}}，同一个事项的多个提醒时刻
 * 各有各的键，不会互相误判。
 *
 * <p>没有 {@code eventId} 的消息（手工插入、上线前已入队的老消息）不做去重，
 * 与旧行为一致。
 *
 * <p>异常处理：{@code onMessage} 抛出异常会让 RocketMQ 触发重试，
 * 累计重试 16 次后进入死信队列。业务异常（如用户不存在）应内部处理并返回，
 * 只在系统异常（如数据库连接失败）时抛出，触发重试。
 * <b>重复消息不抛异常</b>——它已经处理过了，抛出去只会一路重试到死信。
 */
@Component
@RocketMQMessageListener(
        consumerGroup = "life-mind-notification-notify-consumer",
        topic = "life-mind-notification"
)
public class NotificationConsumer implements RocketMQListener<NotificationEvent> {
    private final NotificationService notificationService;

    public NotificationConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void onMessage(NotificationEvent message) {
        notificationService.sendNotification(message);
    }
}
