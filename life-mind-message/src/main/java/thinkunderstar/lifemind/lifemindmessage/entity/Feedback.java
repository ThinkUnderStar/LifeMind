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
 * 用户反馈表
 */
@Data
@TableName("feedback")
public class Feedback {

    /**
     * 反馈ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 反馈用户ID
     */
    private Long userId;

    /**
     * 反馈类型：1-功能建议 2-Bug反馈 3-体验问题 4-其他
     */
    private Integer type;

    /**
     * 反馈内容
     */
    private String content;

    /**
     * 反馈图片地址列表，JSON数组格式字符串
     */
    private String images;

    /**
     * 联系方式（邮箱/手机号等，便于官方回复，可空）
     */
    private String contact;

    /**
     * 处理状态：0-待处理 1-处理中 2-已回复 3-已关闭
     */
    private Integer status;

    /**
     * 管理员回复内容
     */
    private String reply;

    /**
     * 回复人（管理员）用户ID
     */
    private Long replyUserId;

    /**
     * 回复时间
     */
    private LocalDateTime replyTime;

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
