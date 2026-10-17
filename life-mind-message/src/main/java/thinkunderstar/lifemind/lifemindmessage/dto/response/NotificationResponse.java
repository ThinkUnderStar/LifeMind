package thinkunderstar.lifemind.lifemindmessage.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知返回信息
 *
 * <p>分页查询通知列表时返回的单条通知数据，字段与 {@code Notification} 实体对应，
 * 隐藏了逻辑删除等内部字段。
 */
@Data
public class NotificationResponse {

    /**
     * 通知ID
     */
    private Long id;

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
     * 发送者用户ID，系统通知为 null
     */
    private Long senderId;

    /**
     * 关联业务类型，如 post/comment/feedback/task 等，可空
     */
    private String bizType;

    /**
     * 关联业务ID，字符串形式以兼容各类业务主键
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
     * 是否已读：0-未读 1-已读
     */
    private Integer isRead;

    /**
     * 已读时间
     */
    private LocalDateTime readTime;

    /**
     * 扩展数据，JSON格式字符串，存放业务附加参数
     */
    private String extra;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
