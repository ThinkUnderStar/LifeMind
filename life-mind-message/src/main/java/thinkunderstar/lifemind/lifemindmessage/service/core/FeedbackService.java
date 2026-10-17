package thinkunderstar.lifemind.lifemindmessage.service.core;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.servlet.http.HttpServletRequest;
import thinkunderstar.lifemind.lifemindmessage.common.Result;
import thinkunderstar.lifemind.lifemindmessage.dto.request.FeedbackProcessRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.request.FeedbackQueryRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.request.FeedbackSubmitRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.response.FeedbackResponse;

public interface FeedbackService {

    /**
     * 提交反馈。
     *
     * <p>保存反馈后，会给提交人发送一条"反馈已收到"的通知。
     * 用户身份取自网关注入的 {@code LifeMind-User-Id} 请求头。
     *
     * @param submitRequest 提交反馈请求参数
     * @param request       当前请求，用于从请求头解析用户 ID
     * @return 操作结果
     */
    Result<Void> submitFeedback(FeedbackSubmitRequest submitRequest, HttpServletRequest request);

    /**
     * 分页查询全部反馈（管理员视角）。
     *
     * <p>管理员可查看所有用户提交的反馈，不按用户过滤；按反馈类型/处理状态筛选，
     * 不传则查看全部。按管理员用户 ID 限流。
     *
     * @param queryRequest 查询请求参数
     * @param request      当前请求，用于从请求头解析管理员用户 ID（限流）
     * @return 分页反馈列表
     */
    Result<IPage<FeedbackResponse>> getFeedbackPage(FeedbackQueryRequest queryRequest, HttpServletRequest request);

    /**
     * 处理反馈。
     *
     * <p>管理员填写回复并更新处理状态后，会给反馈提交人发送一条"反馈已处理"的通知。
     * 回复人（管理员）用户 ID 取自网关注入的 {@code LifeMind-User-Id} 请求头。
     *
     * @param processRequest 处理反馈请求参数
     * @param request        当前请求，用于从请求头解析用户 ID
     * @return 操作结果
     */
    Result<Void> processFeedback(FeedbackProcessRequest processRequest, HttpServletRequest request);
}
