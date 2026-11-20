package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量变更完成状态。
 *
 * <p>为什么单独开一个接口而不是让调用方循环单条：agent 的高频诉求是
 * 「把今天的待办都标完成」，循环意味着 N 次 HTTP + N 次事务，
 * 中途失败还会留下「一半完成一半没有」且无法回滚。
 *
 * <p>但批量接口本身也不做整体事务：一条失败不该把已经成功的回滚掉，
 * 那样用户重试时会把成功的再做一遍。所以逐条执行、逐条回报，
 * 结果里明确列出哪几条失败、为什么失败。
 */
@Data
public class ItemBatchCompleteRequest {

    /**
     * 事项ID列表，最多 100 条
     */
    @NotEmpty(message = "事项ID列表不能为空")
    @Size(max = 100, message = "一次最多处理 100 条")
    private List<@NotNull(message = "事项ID不能为空") Long> ids;

    /**
     * true-全部标记完成 false-全部取消完成
     */
    @NotNull(message = "完成状态不能为空")
    private Boolean completed;
}
