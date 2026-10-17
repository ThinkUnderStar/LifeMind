package thinkunderstar.lifemind.lifemindmessage.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取反馈列表请求参数
 *
 * <p>分页查询当前登录用户提交的反馈，操作的是登录用户自己，用户身份取自网关注入的请求头，
 * 不需要前端传用户 ID。
 *
 * <p>分页参数有默认值（第 1 页、每页 10 条），前端可不传；其余过滤条件可选。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeedbackQueryRequest {

    /**
     * 页码，从 1 开始，默认 1
     */
    private Integer pageNum = 1;

    /**
     * 每页条数，默认 10
     */
    private Integer pageSize = 10;

    /**
     * 反馈类型过滤（可空）：1-功能建议 2-Bug反馈 3-体验问题 4-其他
     */
    private Integer type;

    /**
     * 处理状态过滤（可空）：0-待处理 1-处理中 2-已回复 3-已关闭
     */
    private Integer status;
}
