package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 删除重复系列（部分或全部）。
 *
 * <p>两种范围的区别是「系列还留不留」：
 * <ul>
 *     <li>{@code all}：连同系列一起删掉，列表里再也看不到；</li>
 *     <li>{@code future}：只让「从这一次起」不再发生，系列保留到锚点前一天，
 *         之前的实例和系列本身都还在——用户改主意时还能看见原来是怎么安排的。</li>
 * </ul>
 *
 * <p>已经发生过的实例两种范围下都保留：删规则不等于抹掉历史。
 */
@Data
public class SeriesDeleteRequest {

    /**
     * 系列ID
     */
    @NotNull(message = "系列ID不能为空")
    private Long id;

    /**
     * 删除范围：all-整个系列 future-从锚点那次起往后
     */
    @NotBlank(message = "删除范围不能为空")
    @Pattern(regexp = "all|future", message = "删除范围只能是 all 或 future")
    private String scope;

    /**
     * 锚点实例ID：{@code scope=future} 时必填
     */
    private Long anchorItemId;
}
