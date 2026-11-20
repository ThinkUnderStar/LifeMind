package thinkunderstar.lifemind.lifemindschedule.service.core;

import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesCreateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesDeleteRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesQueryRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.SeriesUpdateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.response.SeriesResponse;

import java.util.List;

/**
 * 重复系列服务。
 *
 * <p>它管的是「规则」以及「规则展开出来的那批实例」，不重复实现单条事项的逻辑：
 * 单条的完成、取消、改时间仍然走 {@code ItemService}——展开出来的实例就是普通事项，
 * 多一条 series_id 而已。
 *
 * <p>只有三个写入口：创建（含展开）、按范围修改、按范围删除。
 * 「只改这一次」不在这里，调 {@code ItemService.update} 即可。
 */
public interface SeriesService {

    /**
     * 创建系列并按规则展开成多条事项。
     *
     * <p>幂等：带 {@code requestId} 时重复提交只返回上次的结果，不会展开第二套。
     *
     * @param request 创建请求
     * @return 新建的系列，含展开后的实例条数与首尾实例ID
     */
    Result<SeriesResponse> create(SeriesCreateRequest request);

    /**
     * 按范围修改系列。
     *
     * <p>规则字段变了会删掉对应范围内的未来实例再按新规则重建；只改内容则批量写回未来实例。
     *
     * @param request 修改请求
     * @return 修改后的系列
     */
    Result<SeriesResponse> update(SeriesUpdateRequest request);

    /**
     * 按范围删除。
     *
     * <p>{@code all} 连系列一起删；{@code future} 只终止锚点之后的部分，系列保留到锚点前一天。
     * 已经发生的实例两处都保留。
     *
     * @param request 删除请求
     * @return 空结果
     */
    Result<Void> delete(SeriesDeleteRequest request);

    /**
     * 列出当前用户的系列。
     *
     * @param request 过滤条件，可为空条件
     * @return 系列列表，按创建时间倒序，最多 100 条
     */
    Result<List<SeriesResponse>> list(SeriesQueryRequest request);

    /**
     * 系列详情。
     *
     * @param seriesId 系列ID
     * @return 系列详情，含实例条数与首尾实例ID
     */
    Result<SeriesResponse> detail(Long seriesId);
}
