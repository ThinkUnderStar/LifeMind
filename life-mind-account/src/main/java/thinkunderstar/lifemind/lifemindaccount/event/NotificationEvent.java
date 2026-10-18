package thinkunderstar.lifemind.lifemindaccount.event;

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
     * 扩展数据，JSON 格式字符串，存放业务附加参数
     */
    private String extra;
}