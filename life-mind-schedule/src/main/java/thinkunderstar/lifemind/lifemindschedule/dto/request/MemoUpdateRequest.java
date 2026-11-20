package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改备忘录。
 *
 * <p>部分更新：null 表示不改。正文若要清空，传空串会被 Service 拒绝——
 * 一条没有正文的备忘录没有存在意义，想删就直接删。
 */
@Data
public class MemoUpdateRequest {

    /**
     * 备忘录ID
     */
    @NotNull(message = "备忘录ID不能为空")
    private Long id;

    /**
     * 标题
     */
    @Size(max = 200, message = "标题不能超过 200 个字")
    private String title;

    /**
     * 正文
     */
    private String content;

    /**
     * 标签
     */
    @Size(max = 255, message = "标签总长度不能超过 255 个字")
    private String tags;

    /**
     * 是否置顶
     */
    @Min(value = 0, message = "置顶标记只能是 0 或 1")
    @Max(value = 1, message = "置顶标记只能是 0 或 1")
    private Integer pinned;
}
