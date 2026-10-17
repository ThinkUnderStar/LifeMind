package thinkunderstar.lifemind.lifemindmessage.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 处理反馈请求参数
 *
 * <p>管理员处理一条反馈：填写回复内容并更新处理状态，处理完会给反馈提交人发送一条通知。
 * 回复人（管理员）用户 ID 取自网关注入的请求头，不需要前端传。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeedbackProcessRequest {

    /**
     * 反馈ID
     */
    private Long id;

    /**
     * 处理状态：1-处理中 2-已回复 3-已关闭
     */
    private Integer status;

    /**
     * 管理员回复内容
     */
    private String reply;
}
