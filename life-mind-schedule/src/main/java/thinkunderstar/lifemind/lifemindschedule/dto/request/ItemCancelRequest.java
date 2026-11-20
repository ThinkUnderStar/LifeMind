package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 取消事项（不做了，但保留痕迹）。
 *
 * <p>与删除的区别：删除是逻辑删除，列表里彻底看不见；
 * 取消是把 {@code status} 置为 2，仍然能在「已取消」里翻到，也能一键恢复。
 * 用户说「这个会不开了」时，删掉会让历史凭空少一件事，取消才是对的动作。
 */
@Data
public class ItemCancelRequest {

    /**
     * 事项ID
     */
    @NotNull(message = "事项ID不能为空")
    private Long id;
}
