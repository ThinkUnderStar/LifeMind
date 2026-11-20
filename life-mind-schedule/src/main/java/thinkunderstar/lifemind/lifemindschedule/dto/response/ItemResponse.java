package thinkunderstar.lifemind.lifemindschedule.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事项的展示对象（日程 / 待办共用）。
 *
 * <p>{@code remindOffsets} 只带「尚未发送」的提醒偏移：
 * 前端展示「提前 30 分钟」这类文案用，历史已发送的提醒不该出现在编辑表单里。
 * 列表接口不填这个字段（避免每条都查一次提醒表），只有详情接口会填。
 */
@Data
public class ItemResponse {

    /**
     * 事项ID
     */
    private Long id;

    /**
     * 事项类型：schedule-日程 todo-待办
     */
    private String itemType;

    /**
     * 标题
     */
    private String title;

    /**
     * 详细内容
     */
    private String content;

    /**
     * 地点，仅日程有值
     */
    private String location;

    /**
     * 开始时间，仅日程有值
     */
    private LocalDateTime startTime;

    /**
     * 结束时间，可空
     */
    private LocalDateTime endTime;

    /**
     * 截止时间，仅待办有值
     */
    private LocalDateTime dueTime;

    /**
     * 是否全天：0-否 1-是
     */
    private Integer allDay;

    /**
     * 优先级：0-低 1-中 2-高
     */
    private Integer priority;

    /**
     * 状态：0-未完成 1-已完成 2-已取消
     */
    private Integer status;

    /**
     * 完成时间
     */
    private LocalDateTime completedTime;

    /**
     * 标签，逗号分隔
     */
    private String tags;

    /**
     * 尚未发送的提醒偏移（分钟），仅详情接口填充
     */
    private List<Integer> remindOffsets;

    /**
     * 幂等键，创建时传了就原样返回，供调用方核对「这次是不是重复提交」
     */
    private String requestId;

    /**
     * 数据来源：web-前端手写 agent-智能体代操作
     */
    private String source;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
