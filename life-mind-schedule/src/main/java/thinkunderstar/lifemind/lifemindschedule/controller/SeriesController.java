package thinkunderstar.lifemind.lifemindschedule.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesCreateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesDeleteRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesQueryRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesUpdateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.response.SeriesResponse;
import thinkunderstar.lifemind.lifemindschedule.service.core.SeriesService;

import java.util.List;

/**
 * 重复系列接口。
 *
 * <p><b>五个接口</b>
 * <ul>
 *     <li>POST   /series/create     创建规则并按规则展开成多条事项</li>
 *     <li>PUT    /series/update     按范围修改（all / future）</li>
 *     <li>POST   /series/delete     按范围删除（all / future）</li>
 *     <li>GET    /series/list       我的所有重复计划</li>
 *     <li>GET    /series/{id}       系列详情</li>
 * </ul>
 *
 * <p>为什么删除用 POST + body 而不是 DELETE + query：范围参数里带锚点实例 id，
 * 三个参数拼在查询串里既难读又容易漏传；而且「删一部分」本质是带条件的变更操作，
 * 不是一个纯粹的「删掉这个资源」。
 *
 * <p>「只改这一次」不在这里：展开出来的每条实例都是普通事项，
 * 直接调 {@code PUT /item/update}。
 *
 * <p>全部不接受前端传 userId：身份由网关注入的 {@code LifeMind-User-Id} 还原。
 */
@RestController
@RequestMapping("/series")
public class SeriesController {

    private final SeriesService seriesService;

    public SeriesController(SeriesService seriesService) {
        this.seriesService = seriesService;
    }

    /**
     * 创建重复系列。
     *
     * <p>{@code baseTime} 对两种类型含义不同：日程填开始时间，待办填截止时间。
     * {@code countLimit} 与 {@code untilDate} 至少填一个——不提供无限重复。
     *
     * <p>带 {@code requestId} 时幂等：重复提交只返回上次的结果，不会展开第二套。
     */
    @PostMapping("/create")
    public Result<SeriesResponse> create(@Valid @RequestBody SeriesCreateRequest request) {
        return seriesService.create(request);
    }

    /**
     * 按范围修改系列。
     *
     * <p>{@code scope=all} 改整个系列；{@code scope=future} 只改锚点那次及以后，
     * 此时 {@code anchorItemId} 必填。规则字段一旦传入就触发实例重建。
     */
    @PutMapping("/update")
    public Result<SeriesResponse> update(@Valid @RequestBody SeriesUpdateRequest request) {
        return seriesService.update(request);
    }

    /**
     * 按范围删除系列。
     *
     * <p>{@code scope=all} 连系列一起删；{@code scope=future} 只终止锚点之后的部分，
     * 系列保留到锚点前一天。已经发生的实例两种范围下都保留。
     */
    @PostMapping("/delete")
    public Result<Void> delete(@Valid @RequestBody SeriesDeleteRequest request) {
        return seriesService.delete(request);
    }

    /**
     * 我的所有重复计划。
     *
     * <p>不分页：重复计划是「习惯」级别的数量，十几条就算多了。
     * 每条带 {@code itemCount / firstItemId / lastItemId}，agent 据此回答
     * 「还剩几次、下一次是哪条」。
     */
    @GetMapping("/list")
    public Result<List<SeriesResponse>> list(@ModelAttribute SeriesQueryRequest queryRequest) {
        return seriesService.list(queryRequest);
    }

    /**
     * 系列详情。
     */
    @GetMapping("/{id}")
    public Result<SeriesResponse> detail(@PathVariable("id") Long id) {
        return seriesService.detail(id);
    }
}
