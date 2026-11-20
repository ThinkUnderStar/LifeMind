package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量删除事项。
 *
 * <p>用 POST 而不是 DELETE：DELETE 带请求体在部分代理/网关下会被丢弃，
 * 而这个接口的语义又离不开一批 id。路径按 /item/batch/delete 写，
 * 动作清楚，也不必让调用方拼一个超长的 ids 查询串。
 */
@Data
public class ItemBatchDeleteRequest {

    /**
     * 事项ID列表，最多 100 条
     */
    @NotEmpty(message = "事项ID列表不能为空")
    @Size(max = 100, message = "一次最多处理 100 条")
    private List<@NotNull(message = "事项ID不能为空") Long> ids;
}
