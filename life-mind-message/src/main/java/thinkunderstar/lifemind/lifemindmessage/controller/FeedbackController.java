package thinkunderstar.lifemind.lifemindmessage.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import thinkunderstar.lifemind.lifemindmessage.common.Result;
import thinkunderstar.lifemind.lifemindmessage.dto.request.FeedbackProcessRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.request.FeedbackQueryRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.request.FeedbackSubmitRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.response.FeedbackResponse;
import thinkunderstar.lifemind.lifemindmessage.service.core.FeedbackService;

/**
 * 用户反馈接口。
 */
@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * 提交反馈。
     *
     * <p>请求体：type（反馈类型）、content（反馈内容）、images（图片，可空）、contact（联系方式，可空）。
     * 保存成功后会给提交人发送一条"反馈已收到"的通知。
     * 用户身份由网关注入的 {@code LifeMind-User-Id} 请求头还原，不需要前端传用户 ID。
     */
    /**
     * 提交反馈（带截图）。
     *
     * <p>请求为 {@code multipart/form-data}，分两个 part：
     * <ul>
     *     <li>{@code data}：一段 JSON，字段为 type、content、contact；</li>
     *     <li>{@code file}：截图，可空。</li>
     * </ul>
     *
     * <p>图片单独走一个 part 而不是转 base64 塞进 JSON：服务端直接把流写进 MinIO，
     * 不必在内存里先拼出一份 base64 再解码。
     *
     * <p>旧签名 {@code @RequestBody} + {@code @RequestParam file} 是收不到图的——
     * {@code @RequestBody} 会把整个请求体交给 Jackson 按 JSON 解析，
     * multipart 的请求体不是 JSON，还没进方法就被拒了。所以按 Content-Type 拆成两个映射。
     */
    @PostMapping(value = "/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Void> submitFeedbackWithImage(
            @RequestPart(value = "data", required = false) FeedbackSubmitRequest submitRequest,
            @RequestPart(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) {
        return feedbackService.submitFeedback(submitRequest, file, request);
    }

    /**
     * 提交反馈（纯 JSON，不带图）。
     *
     * <p>留给不带截图的调用方与手工调试：想快速验一条链路时只发 JSON 最省事。
     * 两个映射靠 Content-Type 区分，multipart 的优先匹配上面那条。
     */
    @PostMapping(value = "/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> submitFeedbackJson(@RequestBody FeedbackSubmitRequest submitRequest,
                                          HttpServletRequest request) {
        return feedbackService.submitFeedback(submitRequest, null, request);
    }

    /**
     * 我提交过的反馈。
     *
     * <p>只查当前登录用户自己的，按创建时间倒序，带上处理状态与回复。
     * 与 {@code /feedback/page} 的区别就在过滤条件由谁决定：那条是管理员视角，
     * 按类型/状态筛全部人的；这条的用户 ID 只来自网关注入的 {@code LifeMind-User-Id}，
     * 前端传什么都改不了要看谁的数据。
     *
     * <p>分页与筛选参数复用 {@link FeedbackQueryRequest}：pageNum、pageSize、type、status 均可选。
     */
    @GetMapping("/mine")
    public Result<IPage<FeedbackResponse>> getMyFeedback(
            @ModelAttribute FeedbackQueryRequest queryRequest,
            HttpServletRequest request
    ) {
        return feedbackService.getMyFeedback(queryRequest, request);
    }

    /**
     * 分页查询全部反馈（管理员）。
     *
     * <p>请求参数为查询条件：pageNum、pageSize、type、status，均为可选；
     * 管理员可查看所有用户提交的反馈，传 type/status 则按类别/处理状态筛选，不传则查看全部。
     *
     * <p>返回分页反馈列表，按创建时间倒序（最新反馈在前），分页信息由 MyBatis-Plus 的 Page 携带。
     */
    @GetMapping("/page")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<IPage<FeedbackResponse>> getFeedbackPage(
            @ModelAttribute FeedbackQueryRequest queryRequest,
            HttpServletRequest request
    ) {
        return feedbackService.getFeedbackPage(queryRequest, request);
    }

    /**
     * 处理反馈（管理员）。
     *
     * <p>请求体：id（反馈ID）、status（处理状态）、reply（回复内容）。
     * 处理成功后会给反馈提交人发送一条"反馈已处理"的通知。
     * 操作人（管理员）身份由网关注入的 {@code LifeMind-User-Id} 请求头还原，不需要前端传。
     */
    @PostMapping("/process")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> processFeedback(@RequestBody FeedbackProcessRequest processRequest,
                                        HttpServletRequest request) {
        return feedbackService.processFeedback(processRequest, request);
    }
}
