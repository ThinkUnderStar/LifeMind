package thinkunderstar.lifemind.lifemindmessage.service.core.impl;

import cn.hutool.core.bean.BeanUtil;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindmessage.common.ResultCode;
import thinkunderstar.lifemind.lifemindmessage.entity.Notification;
import thinkunderstar.lifemind.lifemindmessage.event.NotificationEvent;
import thinkunderstar.lifemind.lifemindmessage.exception.BusinessException;
import thinkunderstar.lifemind.lifemindmessage.service.core.NotificationService;
import thinkunderstar.lifemind.lifemindmessage.service.wrapper.NotificationWrapperService;
import thinkunderstar.lifemind.lifemindmessage.util.RedisTokenBucketLimiter;

@Service
public class NotificationServiceImpl implements NotificationService {

    /**
     * 单接收人限流：桶容量 10（允许的突发次数），每秒补充 1 个令牌。
     * 挡住对同一用户的通知轰炸，同时给正常互动留足突发余量。
     */
    private static final long NOTIFICATION_USER_CAPACITY = 10L;
    private static final double NOTIFICATION_USER_RATE = 1.0;

    private final RedisTokenBucketLimiter rateLimiter;
    private final NotificationWrapperService notificationWrapperService;

    public NotificationServiceImpl(RedisTokenBucketLimiter rateLimiter, NotificationWrapperService notificationWrapperService) {
        this.rateLimiter = rateLimiter;
        this.notificationWrapperService = notificationWrapperService;
    }

    @Override
    public void sendNotification(NotificationEvent notificationEvent) {

        // ========== 1. 参数非空校验 ==========
        if (notificationEvent == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "通知事件不能为空");
        }

        // ========== 2. 字段合法性校验 ==========
        validateNotificationEvent(notificationEvent);

        // ========== 3. 按接收人限流 ==========
        // 系统通知 senderId 为 null，仍按 userId 限流，防止对单一用户刷通知
        if (!rateLimiter.tryAcquireByUser(notificationEvent.getUserId(),
                NOTIFICATION_USER_CAPACITY, NOTIFICATION_USER_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "通知发送过于频繁，请稍后再试");
        }

        //发送消息的业务逻辑
        Notification notification = new Notification();
        //调用hutool包中的工具类，将对应字段映射到新创建的消息对象中
        BeanUtil.copyProperties(notificationEvent, notification);

        notification.setIsRead(0);
        notification.setDeleted(0);

        //落库
        notificationWrapperService.save(notification);
    }

    /**
     * 校验通知事件各字段是否合法合规。
     */
    private void validateNotificationEvent(NotificationEvent event) {

        // 接收人 ID：必填，且必须为正数
        if (event.getUserId() == null || event.getUserId() <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "接收人用户ID不合法");
        }

        // 通知类型：必填，长度上限 50
        if (event.getType() == null || event.getType().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "通知类型不能为空");
        }
        if (event.getType().length() > 50) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "通知类型长度超出限制");
        }

        // 通知标题：必填，长度上限 100
        if (event.getTitle() == null || event.getTitle().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "通知标题不能为空");
        }
        if (event.getTitle().length() > 100) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "通知标题长度超出限制");
        }

        // 通知内容：必填，长度上限 500
        if (event.getContent() == null || event.getContent().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "通知内容不能为空");
        }
        if (event.getContent().length() > 500) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "通知内容长度超出限制");
        }

        // 发送者 ID：可空，不为空时必须为正数
        if (event.getSenderId() != null && event.getSenderId() <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "发送者用户ID不合法");
        }

        // 业务类型：可空，长度上限 50
        if (event.getBizType() != null && event.getBizType().length() > 50) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "业务类型长度超出限制");
        }

        // 业务 ID：可空，长度上限 64
        if (event.getBizId() != null && event.getBizId().length() > 64) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "业务ID长度超出限制");
        }

        // 跳转地址：可空，长度上限 255
        if (event.getJumpUrl() != null && event.getJumpUrl().length() > 255) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "跳转地址长度超出限制");
        }

        // 通知级别：可空，不为空时只允许 0-普通 1-重要 2-紧急
        if (event.getLevel() != null && (event.getLevel() < 0 || event.getLevel() > 2)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "通知级别不合法");
        }

        // 扩展数据：可空，长度上限 1000
        if (event.getExtra() != null && event.getExtra().length() > 1000) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "扩展数据长度超出限制");
        }
    }
}
