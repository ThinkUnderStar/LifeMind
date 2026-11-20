package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建备忘录。
 *
 * <p>标题可空、正文必填：随手记一句话时不该被迫起标题，
 * 但完全没有内容的备忘录没有意义。
 */
@Data
public class MemoCreateRequest {

    /**
     * 标题，可为空
     */
    @Size(max = 200, message = "标题不能超过 200 个字")
    private String title;

    /**
     * 正文
     */
    @NotBlank(message = "内容不能为空")
    private String content;

    /**
     * 标签，逗号分隔
     */
    @Size(max = 255, message = "标签总长度不能超过 255 个字")
    private String tags;

    /**
     * 是否置顶：0-否 1-是，默认 0
     */
    @Min(value = 0, message = "置顶标记只能是 0 或 1")
    @Max(value = 1, message = "置顶标记只能是 0 或 1")
    private Integer pinned;
}
