package thinkunderstar.lifemind.lifemindmessage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用通知表
 */
@Data
@TableName("notification")
public class Notification {

    /**
     * 通知ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 接收人用户ID
     */
    private Long userId;

    /**
     * 通知类型：system-系统通知 like-点赞 comment-评论 reply-回复 follow-关注 feedback-反馈等，字符串编码便于业务扩展
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
     * 发送者用户ID，系统通知为NULL
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
     * 逻辑删除：0-未删除 1-已删除
     */
    @TableLogic
    private Integer deleted;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
