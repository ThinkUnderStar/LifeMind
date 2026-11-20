package thinkunderstar.lifemind.lifemindmessage.event;

import lombok.Data;

/**
 * 服务间通知消息体。
 *
 * <p>由生产者（账户服务、日历服务等）在事件发生时投递到 MQ，
 * 消费者（通知服务）接收后组装成 {@code Notification} 实体落库并分发。
 *
 * <p>字段与 {@code Notification} 实体一一对应，但不包含由消费者生成的字段
 * （id、isRead、readTime、deleted、createTime、updateTime）。
 */
@Data
public class NotificationEvent {

    /**
     * 接收人用户 ID
     */
    private Long userId;

    /**
     * 通知类型：system-系统通知 like-点赞 comment-评论 reply-回复 follow-关注 feedback-反馈等
     */
    private String type;

    /**
     * 通知标题
     */
    private String title;

    /**
     * 通知内容
     */
    private String content;

    /**
     * 发送者用户 ID，系统通知为 null
     */
    private Long senderId;

    /**
     * 关联业务类型，如 post/comment/feedback/task 等，可空
     */
    private String bizType;

    /**
     * 关联业务 ID，字符串形式以兼容各类业务主键
     */
    private String bizId;

    /**
     * 点击通知后的跳转地址
     */
    private String jumpUrl;

    /**
     * 通知级别：0-普通 1-重要 2-紧急
     */
    private Integer level;

    /**
     * 是否需要弹窗提醒：0-否 1-是；null 按 0 处理
     *
     * <p>由生产方按业务语义决定，不要从 level 推导：级别表达「多要紧」，
     * 本字段表达「要不要打断用户当前操作」，两者不总是同向。
     */
    private Integer needPopup;

    /**
     * 事件幂等键：生产方生成，必须能唯一标识「这一次业务事件」。
     *
     * <p>RocketMQ 只保证至少投递一次，重复消息会被本字段挡掉。格式约定
     * {@code {来源}_{主键}}，例如 {@code schedule_reminder_123}——带来源前缀是
     * 因为未来会有多个生产方，各自的主键空间独立，裸数字会互相撞车。
     *
     * <p>可空：手工插入或系统直发的通知可以不给，唯一索引允许多个 NULL。
     * 需要幂等保护的场景必须给，否则重复消费仍会落两行。
     */
    private String eventId;

    /**
     * 扩展数据，JSON 格式字符串，存放业务附加参数
     */
    private String extra;
}