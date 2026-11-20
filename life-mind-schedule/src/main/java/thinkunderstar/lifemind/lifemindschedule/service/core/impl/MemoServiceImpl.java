package thinkunderstar.lifemind.lifemindschedule.service.core.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.common.ResultCode;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoCreateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoPinRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoQueryRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoUpdateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.response.MemoResponse;
import thinkunderstar.lifemind.lifemindschedule.entity.Memo;
import thinkunderstar.lifemind.lifemindschedule.exception.BusinessException;
import thinkunderstar.lifemind.lifemindschedule.service.core.MemoService;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.MemoWrapperService;
import thinkunderstar.lifemind.lifemindschedule.util.UserContext;

/**
 * 备忘录服务实现。
 */
@Slf4j
@Service
public class MemoServiceImpl implements MemoService {

    private static final int NOT_PINNED = 0;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final MemoWrapperService memoWrapperService;

    public MemoServiceImpl(MemoWrapperService memoWrapperService) {
        this.memoWrapperService = memoWrapperService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<MemoResponse> create(MemoCreateRequest request) {
        Long userId = UserContext.currentUserId();

        Memo memo = new Memo();
        BeanUtil.copyProperties(request, memo);
        memo.setUserId(userId);
        memo.setPinned(request.getPinned() == null ? NOT_PINNED : request.getPinned());

        // 标题允许为空，但全空白的标题没有意义，统一置 null 让前端走「正文首行」兜底
        if (memo.getTitle() != null && memo.getTitle().isBlank()) {
            memo.setTitle(null);
        }

        memoWrapperService.save(memo);
        log.info("备忘录已创建：id={} userId={}", memo.getId(), userId);
        return Result.success(toResponse(memo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<MemoResponse> update(MemoUpdateRequest request) {
        Long userId = UserContext.currentUserId();
        Memo memo = requireOwned(userId, request.getId());

        if (request.getTitle() != null) {
            memo.setTitle(request.getTitle().isBlank() ? null : request.getTitle());
        }
        if (request.getContent() != null) {
            // 允许改内容，但不允许改成空白：一条没有正文的备忘录没有存在意义，
            // 想隐藏它应该用删除
            if (request.getContent().isBlank()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "内容不能为空");
            }
            memo.setContent(request.getContent());
        }
        if (request.getTags() != null) {
            memo.setTags(request.getTags());
        }
        if (request.getPinned() != null) {
            memo.setPinned(request.getPinned());
        }

        memoWrapperService.updateById(memo);
        log.info("备忘录已更新：id={} userId={}", memo.getId(), userId);
        return Result.success(toResponse(memo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(Long memoId) {
        Long userId = UserContext.currentUserId();
        Memo memo = requireOwned(userId, memoId);
        memoWrapperService.removeById(memo.getId());
        log.info("备忘录已删除：id={} userId={}", memoId, userId);
        return Result.success();
    }

    @Override
    public Result<MemoResponse> detail(Long memoId) {
        Long userId = UserContext.currentUserId();
        return Result.success(toResponse(requireOwned(userId, memoId)));
    }

    @Override
    public Result<IPage<MemoResponse>> page(MemoQueryRequest queryRequest) {
        Long userId = UserContext.currentUserId();

        long pageNum = queryRequest.getPageNum() == null || queryRequest.getPageNum() <= 0
                ? 1 : queryRequest.getPageNum();
        long pageSize = queryRequest.getPageSize() == null || queryRequest.getPageSize() <= 0
                ? DEFAULT_PAGE_SIZE : Math.min(queryRequest.getPageSize(), MAX_PAGE_SIZE);

        LambdaQueryWrapper<Memo> wrapper = new LambdaQueryWrapper<Memo>()
                .eq(Memo::getUserId, userId);

        if (StringUtils.hasText(queryRequest.getKeyword())) {
            String keyword = queryRequest.getKeyword().trim();
            wrapper.and(q -> q.like(Memo::getTitle, keyword)
                    .or(w -> w.like(Memo::getContent, keyword)));
        }
        // 标签用 like 而不是 eq：标签存在一个逗号串里，精确匹配会漏掉组合标签
        if (StringUtils.hasText(queryRequest.getTags())) {
            wrapper.like(Memo::getTags, queryRequest.getTags().trim());
        }

        // 置顶在前，其余按最近修改
        wrapper.orderByDesc(Memo::getPinned).orderByDesc(Memo::getUpdateTime);

        Page<Memo> page = memoWrapperService.page(new Page<>(pageNum, pageSize), wrapper);
        return Result.success(page.convert(this::toResponse));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> pin(MemoPinRequest request) {
        Long userId = UserContext.currentUserId();
        Memo memo = requireOwned(userId, request.getId());
        memo.setPinned(Boolean.TRUE.equals(request.getPinned()) ? 1 : 0);
        memoWrapperService.updateById(memo);
        log.info("备忘录置顶状态已变更：id={} pinned={}", memo.getId(), memo.getPinned());
        return Result.success();
    }

    /**
     * 按 id + userId 取备忘录，取不到直接 404。
     */
    private Memo requireOwned(Long userId, Long memoId) {
        if (memoId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "备忘录ID不能为空");
        }
        Memo memo = memoWrapperService.getOne(
                new LambdaQueryWrapper<Memo>()
                        .eq(Memo::getId, memoId)
                        .eq(Memo::getUserId, userId),
                false);
        if (memo == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "备忘录不存在或无权访问");
        }
        return memo;
    }

    private MemoResponse toResponse(Memo memo) {
        return BeanUtil.copyProperties(memo, MemoResponse.class);
    }
}
