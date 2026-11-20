package thinkunderstar.lifemind.lifemindschedule.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
import thinkunderstar.lifemind.lifemindschedule.service.core.ItemService;

import java.util.List;

/**
 * 事项接口：日程与待办共用。
 *
 * <p><b>单条操作</b>
 * <ul>
 *     <li>POST   /item/create        创建（可同时设置提醒，支持 requestId 幂等）</li>
 *     <li>PUT    /item/update        修改（时间变了会重算提醒，clearFields 可清空字段）</li>
 *     <li>PUT    /item/complete      完成 / 取消完成（取消完成也能把「已取消」恢复回来）</li>
 *     <li>PUT    /item/cancel        取消：不做了但留痕，可查可恢复</li>
 *     <li>DELETE /item/{id}          删除（逻辑删除，同时取消未发送的提醒）</li>
 *     <li>GET    /item/{id}          详情（带未发送的提醒偏移）</li>
 *     <li>GET    /item/page          分页查询（类型/状态/关键词/单日/区间/逾期）</li>
 * </ul>
 *
 * <p><b>批量操作（给 agent 用）</b>
 * <ul>
 *     <li>GET  /item/batch?ids=1,2,3     按 id 批量取详情，按入参顺序返回</li>
 *     <li>PUT  /item/batch/complete      批量完成 / 取消完成，逐条回报</li>
 *     <li>POST /item/batch/delete        批量删除，逐条回报</li>
 * </ul>
 *
 * <p>批量接口的路径都写在 {@code /item/{id}} 之前：Spring 会优先匹配字面量路径，
 * 顺序不影响结果，但把「batch」放在前面能避免读代码时误以为它会被当成 id。
 *
 * <p>全部不接受前端传 userId：身份由网关注入的 {@code LifeMind-User-Id} 还原，
 * 归属校验在 Service 层统一做。
 */
@RestController
@RequestMapping("/item")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    /**
     * 创建事项。
     *
     * <p>{@code itemType=schedule} 时 {@code startTime} 必填；
     * {@code itemType=todo} 时只有 {@code dueTime} 有意义，传了 startTime 会被忽略。
     *
     * <p>带 {@code requestId} 时幂等：同一用户重复提交同一个值只建一条，
     * 第二次原样返回第一次的记录。agent 的重试依赖这一点。
     */
    @PostMapping("/create")
    public Result<ItemResponse> create(@Valid @RequestBody ItemCreateRequest request) {
        return itemService.create(request);
    }

    /**
     * 修改事项。
     *
     * <p>字段为 null 表示不改。{@code remindOffsets} 三态：
     * 不传 = 保留原提醒；空列表 = 取消提醒；非空 = 按新偏移重建。
     *
     * <p>要把某个字段改回空，用 {@code clearFields} 列表，
     * 因为 null 已经被「不改」占用了。
     */
    @PutMapping("/update")
    public Result<ItemResponse> update(@Valid @RequestBody ItemUpdateRequest request) {
        return itemService.update(request);
    }

    /**
     * 标记完成 / 取消完成。
     *
     * <p>标记完成会取消尚未发送的提醒——事情做完了不该再弹提醒。
     * 取消完成不会自动恢复提醒，需要重新设置。
     */
    @PutMapping("/complete")
    public Result<Void> complete(@Valid @RequestBody ItemCompleteRequest request) {
        return itemService.complete(request);
    }

    /**
     * 取消事项：不做了，但保留记录。
     *
     * <p>与删除的区别：删除后列表里彻底看不见；取消后仍在「已取消」里，
     * 也能用 {@code PUT /item/complete} 传 {@code completed=false} 恢复。
     * 重复取消是幂等的。
     */
    @PutMapping("/cancel")
    public Result<Void> cancel(@Valid @RequestBody ItemCancelRequest request) {
        return itemService.cancel(request);
    }

    /**
     * 删除事项（逻辑删除）。
     *
     * <p>同时取消该事项尚未发送的提醒；已发送的提醒记录保留，作为历史。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        return itemService.delete(id);
    }

    /**
     * 按 id 批量取详情。
     *
     * <p>给 agent 用：它常常从上一轮结果里拿到一批 id，再想一次性取回最新状态。
     * 循环调单条详情会变成 N 次往返。不存在的 id 直接跳过，不报错——
     * 调用方拿到的条数少于请求条数，本身就是「有 id 不见了」的信号。
     *
     * @param ids 逗号分隔的 id，最多 100 个
     */
    @GetMapping("/batch")
    public Result<List<ItemResponse>> batchDetail(@RequestParam("ids") List<Long> ids) {
        return itemService.listByIds(ids);
    }

    /**
     * 批量标记完成 / 取消完成。
     *
     * <p>不做整体事务：一条失败不影响其余，结果里逐条列出失败原因。
     */
    @PutMapping("/batch/complete")
    public Result<BatchResultResponse> batchComplete(@Valid @RequestBody ItemBatchCompleteRequest request) {
        return itemService.batchComplete(request);
    }

    /**
     * 批量删除。同样逐条回报，一条失败不回滚其余。
     */
    @PostMapping("/batch/delete")
    public Result<BatchResultResponse> batchDelete(@Valid @RequestBody ItemBatchDeleteRequest request) {
        return itemService.batchDelete(request);
    }

    /**
     * 事项详情。
     *
     * <p>比列表多返回 {@code remindOffsets}（尚未发送的提醒偏移），
     * 供编辑表单回填。
     */
    @GetMapping("/{id}")
    public Result<ItemResponse> detail(@PathVariable("id") Long id) {
        return itemService.detail(id);
    }

    /**
     * 分页查询事项。
     *
     * <p>{@code date} 用于日视图（日程看开始时间、待办看截止时间），
     * {@code startDate ~ endDate} 用于周/月视图，同时传时以 {@code date} 为准。
     * {@code overdue=true} 只看逾期待办，此时忽略 itemType 与 status。
     */
    @GetMapping("/page")
    public Result<IPage<ItemResponse>> page(@ModelAttribute ItemQueryRequest queryRequest) {
        return itemService.page(queryRequest);
    }
}
