package thinkunderstar.lifemind.lifemindmessage.consumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import thinkunderstar.lifemind.lifemindmessage.event.NotificationEvent;
import thinkunderstar.lifemind.lifemindmessage.service.core.NotificationService;

/**
 * 通知消息消费者。
 *
 * <p>监听 {@code life-mind-notification} Topic 下的所有事件，统一由
 * 按渠道（email / sms / inbox）分发处理。
 *
 * <p>消费规范：
 * <ul>
 *     <li>消费组：{@code life-mind-notification-notify-consumer}，全局唯一</li>
 *     <li>Topic：{@code life-mind-notification}</li>
 *     <li>不指定 Tag，消费该 Topic 下所有事件，由消息体中的 {@code type} 字段区分</li>
 * </ul>
 *
 * <p>幂等：MQ 保证"至少投递一次"，可能出现重复消息。
 * 消费逻辑需按 {@code eventId} 去重，避免重复发送通知。
 *
 * <p>异常处理：{@code onMessage} 抛出异常会让 RocketMQ 触发重试，
 * 累计重试 16 次后进入死信队列。业务异常（如用户不存在）应内部处理并返回，
 * 只在系统异常（如数据库连接失败）时抛出，触发重试。
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
