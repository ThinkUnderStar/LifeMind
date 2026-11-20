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
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoCreateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoPinRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoQueryRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.request.MemoUpdateRequest;
import thinkunderstar.lifemind.lifemindschedule.dto.response.MemoResponse;
import thinkunderstar.lifemind.lifemindschedule.service.core.MemoService;

/**
 * 备忘录接口。
 *
 * <p>六个接口：create / update / delete / detail / page / pin。
 * 备忘录没有时间点和完成状态，所以没有「完成」类接口。
 */
@RestController
@RequestMapping("/memo")
public class MemoController {

    private final MemoService memoService;

    public MemoController(MemoService memoService) {
        this.memoService = memoService;
    }

    /**
     * 新建备忘录。标题可空，正文必填。
     */
    @PostMapping("/create")
    public Result<MemoResponse> create(@Valid @RequestBody MemoCreateRequest request) {
        return memoService.create(request);
    }

    /**
     * 修改备忘录。字段为 null 表示不改。
     */
    @PutMapping("/update")
    public Result<MemoResponse> update(@Valid @RequestBody MemoUpdateRequest request) {
        return memoService.update(request);
    }

    /**
     * 删除备忘录（逻辑删除）。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        return memoService.delete(id);
    }

    /**
     * 备忘录详情。
     */
    @GetMapping("/{id}")
    public Result<MemoResponse> detail(@PathVariable("id") Long id) {
        return memoService.detail(id);
    }

    /**
     * 分页查询：置顶在前，其余按更新时间倒序。
     */
    @GetMapping("/page")
    public Result<IPage<MemoResponse>> page(@ModelAttribute MemoQueryRequest queryRequest) {
        return memoService.page(queryRequest);
    }

    /**
     * 置顶 / 取消置顶。
     *
     * <p>单独一个接口而不是塞进 update：前端在列表里点置顶时，
     * 不该为了改一个标记而把整条备忘录回传一遍。
     */
    @PutMapping("/pin")
    public Result<Void> pin(@Valid @RequestBody MemoPinRequest request) {
        return memoService.pin(request);
    }
}
