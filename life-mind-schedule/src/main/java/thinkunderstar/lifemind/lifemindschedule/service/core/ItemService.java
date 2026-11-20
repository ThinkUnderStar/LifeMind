package thinkunderstar.lifemind.lifemindschedule.service.core;

import com.baomidou.mybatisplus.core.metadata.IPage;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemBatchCompleteRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemBatchDeleteRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemCancelRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemCompleteRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemCreateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemQueryRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.ItemUpdateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.response.BatchResultResponse;
import thinkunderstar.lifemind.lifemindschedule.dto.response.ItemResponse;

import java.util.List;

/**
 * 事项服务：日程与待办的统一入口。
 *
 * <p>归属校验统一放在这里，而不是让 Controller 先查再调：
 * 每多一个入口就多一次「忘了校验」的机会，而漏一次的代价是能改别人的日程。
 *
 * <p>单条操作失败抛 {@code BusinessException}（HTTP 状态码随之返回）；
 * 批量操作不整体抛，逐条收集结果，理由见 {@link BatchResultResponse}。
 */
public interface ItemService {

    /**
     * 创建事项，并按 {@code remindOffsets} 生成提醒。
     *
     * <p>带 {@code requestId} 时幂等：同一用户重复提交同一个值只会建一条，
     * 第二次直接返回第一次的记录。agent 的重试依赖这一点。
     */
    Result<ItemResponse> create(ItemCreateRequest request);

    /**
     * 修改事项。时间变了会按原提醒偏移重算提醒时刻；
     * 显式传了 {@code remindOffsets} 则按新偏移重建；{@code clearFields} 用于清空字段。
     */
    Result<ItemResponse> update(ItemUpdateRequest request);

    /**
     * 删除事项（逻辑删除），同时取消它尚未发送的提醒。
     */
    Result<Void> delete(Long itemId);

    /**
     * 取消事项（status=2）：不做了，但留在「已取消」里可查可恢复。
     */
    Result<Void> cancel(ItemCancelRequest request);

    /**
     * 标记完成 / 取消完成。
     */
    Result<Void> complete(ItemCompleteRequest request);

    /**
     * 批量标记完成 / 取消完成，逐条回报结果。
     */
    Result<BatchResultResponse> batchComplete(ItemBatchCompleteRequest request);

    /**
     * 批量删除，逐条回报结果。
     */
    Result<BatchResultResponse> batchDelete(ItemBatchDeleteRequest request);

    /**
     * 事项详情，带尚未发送的提醒偏移。
     */
    Result<ItemResponse> detail(Long itemId);

    /**
     * 按 id 批量取详情（只返回属于当前用户的）。
     *
     * <p>给 agent 用：它常常从上一轮结果里拿到一批 id，再想一次性取回最新状态。
     * 循环单查会变成 N 次往返。
     */
    Result<List<ItemResponse>> listByIds(List<Long> ids);

    /**
     * 分页查询：支持类型、状态、关键词、单日、日期区间、逾期过滤。
     */
    Result<IPage<ItemResponse>> page(ItemQueryRequest queryRequest);
}
