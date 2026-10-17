package thinkunderstar.lifemind.lifemindmessage.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
    @PostMapping("/submit")
    public Result<Void> submitFeedback(@RequestBody FeedbackSubmitRequest submitRequest,
                                       HttpServletRequest request) {
        return feedbackService.submitFeedback(submitRequest, request);
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
