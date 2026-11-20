package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 事项完成 / 取消完成。
 *
 * <p>用一个布尔而不是「直接置为已完成」：勾选和取消勾选是同一个动作的两种结果，
 * 拆成两个接口会让前端在点复选框时还要判断当前状态。
 *
 * <p>{@code completed=true} 会同时取消该事项尚未发送的提醒——
 * 事情都做完了还弹提醒，是最容易被骂的体验问题之一。
 */
@Data
public class ItemCompleteRequest {

    /**
     * 事项ID
     */
    @NotNull(message = "事项ID不能为空")
    private Long id;

    /**
     * true-标记完成 false-取消完成
     */
    @NotNull(message = "完成状态不能为空")
    private Boolean completed;
}
