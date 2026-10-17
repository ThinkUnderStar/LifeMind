package thinkunderstar.lifemind.lifemindmessage.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取通知列表请求参数
 *
 * <p>分页查询当前登录用户的通知，操作的是登录用户自己，用户身份取自网关注入的请求头，
 * 不需要前端传用户 ID。
 *
 * <p>分页参数有默认值（第 1 页、每页 10 条），前端可不传；其余过滤条件可选。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class NotificationQueryRequest {

    /**
     * 页码，从 1 开始，默认 1
     */
    private Integer pageNum = 1;

    /**
     * 每页条数，默认 10
     */
    private Integer pageSize = 10;

    /**
     * 通知类型过滤（可空）：system-系统通知 like-点赞 comment-评论 reply-回复 follow-关注 feedback-反馈等
     */
    private String type;

    /**
     * 已读状态过滤（可空）：0-未读 1-已读，不传查询全部
     */
    private Integer isRead;
}
