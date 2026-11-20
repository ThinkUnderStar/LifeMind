package thinkunderstar.lifemind.lifemindschedule.service.core;

import com.baomidou.mybatisplus.core.metadata.IPage;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoCreateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoPinRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoQueryRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoUpdateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.response.MemoResponse;

/**
 * 备忘录服务。
 *
 * <p>与事项分开的原因见建表 SQL 注释：备忘录没有时间点和完成状态，
 * 硬塞进事项表会多出恒为 null 的列。
 */
public interface MemoService {

    /**
     * 新建备忘录。
     */
    Result<MemoResponse> create(MemoCreateRequest request);

    /**
     * 修改备忘录（部分更新，null 表示不改）。
     */
    Result<MemoResponse> update(MemoUpdateRequest request);

    /**
     * 删除备忘录（逻辑删除）。
     */
    Result<Void> delete(Long memoId);

    /**
     * 备忘录详情。
     */
    Result<MemoResponse> detail(Long memoId);

    /**
     * 分页查询：置顶在前，其余按更新时间倒序。
     */
    Result<IPage<MemoResponse>> page(MemoQueryRequest queryRequest);

    /**
     * 置顶 / 取消置顶。
     */
    Result<Void> pin(MemoPinRequest request);
}
