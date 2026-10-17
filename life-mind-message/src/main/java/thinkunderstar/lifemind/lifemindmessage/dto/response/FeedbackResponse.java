package thinkunderstar.lifemind.lifemindmessage.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 反馈返回信息
 *
 * <p>查询反馈列表时返回的单条反馈数据，字段与 {@code Feedback} 实体对应，
 * 隐藏了逻辑删除等内部字段。
 */
@Data
public class FeedbackResponse {

    /**
     * 反馈ID
     */
    private Long id;

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
     * 创建时间
     */
    private LocalDateTime createTime;
}
