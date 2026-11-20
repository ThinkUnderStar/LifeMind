package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 修改事项。
 *
 * <p>采用「部分更新」语义：字段为 null 表示不改，不是清空。
 * 这样前端只传改动的字段即可，不必先 GET 再整条 PUT 回来。
 *
 * <p>「清空某个字段」由 {@link #clearFields} 显式表达，而不是靠传 null：
 * null 已经被「不改」占用了，再用它表示清空就分不清「没传」和「要删掉」。
 * 一开始想砍掉这个能力（判断是低频需求），但 agent 说「把地点去掉」时
 * 除了删了重建没有别的路，代价太大，所以补上。
 *
 * <p>{@code itemType} 不允许修改：日程与待办的时间字段规则不同，类型一改
 * 就要连带清空另一套时间字段，容易造出「是日程但没有开始时间」的脏数据。
 */
@Data
public class ItemUpdateRequest {

    /**
     * 事项ID
     */
    @NotNull(message = "事项ID不能为空")
    private Long id;

    /**
     * 标题
     */
    @Size(max = 200, message = "标题不能超过 200 个字")
    private String title;

    /**
     * 详细内容
     */
    private String content;

    /**
     * 地点，仅日程使用
     */
    @Size(max = 255, message = "地点不能超过 255 个字")
    private String location;

    /**
     * 开始时间：日程可改，改后提醒会自动重算
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 截止时间：待办可改，改后提醒会自动重算
     */
    private LocalDateTime dueTime;

    /**
     * 是否全天
     */
    @Min(value = 0, message = "全天标记只能是 0 或 1")
    @Max(value = 1, message = "全天标记只能是 0 或 1")
    private Integer allDay;

    /**
     * 优先级
     */
    @Min(value = 0, message = "优先级只能是 0、1、2")
    @Max(value = 2, message = "优先级只能是 0、1、2")
    private Integer priority;

    /**
     * 标签，逗号分隔
     */
    @Size(max = 255, message = "标签总长度不能超过 255 个字")
    private String tags;

    /**
     * 提醒偏移。
     *
     * <p>null = 保留原提醒不动；空列表 = 取消全部未发送的提醒；
     * 非空 = 取消旧的未发送提醒后按新的偏移重建。
     * 这个三态区分很重要：前端只改标题时不该把提醒悄悄删掉。
     */
    @Valid
    @Size(max = 10, message = "一个事项最多设置 10 个提醒")
    private List<@Min(value = 0, message = "提醒偏移不能为负数")
                 @Max(value = 10080, message = "提醒最多提前 7 天") Integer> remindOffsets;

    /**
     * 要清空的字段名列表，可选值：content / location / endTime / dueTime / tags。
     *
     * <p>标题和开始时间不在允许范围内：前者是必填，后者是日程的存在前提，
     * 「清空」它们等于造一条脏数据，想这么做得删了重建。
     *
     * <p>与同名字段同时出现时以清空为准（如既传 {@code location="A"}
     * 又在 {@code clearFields} 里写 location，结果为 null）——这种自相矛盾的请求
     * 不该靠执行顺序决定结果，直接让清空赢，行为可预测。
     *
     * <p>清空 {@code dueTime} 会让待办失去提醒基准，此时未发送的提醒会被一并取消。
     */
    private List<String> clearFields;
}
