package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 备忘录置顶 / 取消置顶。
 */
@Data
public class MemoPinRequest {

    /**
     * 备忘录ID
     */
    @NotNull(message = "备忘录ID不能为空")
    private Long id;

    /**
     * true-置顶 false-取消置顶
     */
    @NotNull(message = "置顶状态不能为空")
    private Boolean pinned;
}
