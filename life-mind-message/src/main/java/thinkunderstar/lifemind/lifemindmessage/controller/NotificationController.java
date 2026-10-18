package thinkunderstar.lifemind.lifemindmessage.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import thinkunderstar.lifemind.lifemindmessage.common.Result;
import thinkunderstar.lifemind.lifemindmessage.dto.request.NotificationQueryRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.request.NotificationReadRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.response.NotificationResponse;
import thinkunderstar.lifemind.lifemindmessage.service.core.NotificationService;

@RestController
@RequestMapping("/notification")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 分页查询当前登录用户的通知列表。
     *
     * <p>请求参数为查询条件：pageNum、pageSize、type、isRead，均为可选；
     * 用户身份由网关注入的 {@code LifeMind-User-Id} 请求头还原，不需要前端传用户 ID。
     *
     * <p>返回分页通知列表，按创建时间倒序（新通知在前），分页信息由 MyBatis-Plus 的 Page 携带。
     */
    @GetMapping("/page")
    public Result<IPage<NotificationResponse>> getNotificationPage(
            @ModelAttribute NotificationQueryRequest queryRequest,
            HttpServletRequest request
    ) {
        return notificationService.getNotificationPage(queryRequest, request);
    }

    /**
     * 标记当前登录用户的某条通知为已读。
     *
     * <p>请求体为通知 ID；只允许标记属于自己的通知，用户身份由网关注入的
     * {@code LifeMind-User-Id} 请求头还原。重复标记已读为幂等操作，直接返回成功。
     */
    @PostMapping("/read")
    public Result<Void> markNotificationRead(@RequestBody NotificationReadRequest readRequest,
                                             HttpServletRequest request) {
        return notificationService.markNotificationRead(readRequest, request);
    }
}
