package thinkunderstar.lifemind.lifemindmessage.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提交反馈请求参数
 *
 * <p>用户提交一条反馈，反馈用户取自网关注入的请求头，不需要前端传用户 ID。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeedbackSubmitRequest {

    /**
     * 反馈类型：1-功能建议 2-Bug反馈 3-体验问题 4-其他
     */
    private Integer type;

    /**
     * 反馈内容
     */
    private String content;

    /**
     * 反馈图片地址列表，JSON数组格式字符串，可空
     */
    private String images;

    /**
     * 联系方式（邮箱/手机号等，便于官方回复），可空
     */
    private String contact;
}
