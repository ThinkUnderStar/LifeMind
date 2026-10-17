package thinkunderstar.lifemind.lifemindmessage.service.core;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.servlet.http.HttpServletRequest;
import thinkunderstar.lifemind.lifemindmessage.common.Result;
import thinkunderstar.lifemind.lifemindmessage.dto.request.NotificationQueryRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.response.NotificationResponse;
import thinkunderstar.lifemind.lifemindmessage.event.NotificationEvent;

public interface NotificationService {
    void sendNotification(NotificationEvent notificationEvent);

    /**
     * 分页查询当前登录用户的通知列表。
     *
     * <p>查询条件：页码、每页条数、通知类型、已读状态（均可选），
     * 只查当前登录用户自己的通知，用户身份取自网关注入的 {@code LifeMind-User-Id} 请求头。
     *
     * @param queryRequest 查询请求参数
     * @param request      当前请求，用于从请求头解析用户 ID
     * @return 分页通知列表
     */
    Result<IPage<NotificationResponse>> getNotificationPage(NotificationQueryRequest queryRequest,
                                                            HttpServletRequest request);
}
