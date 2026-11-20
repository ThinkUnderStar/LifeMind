package thinkunderstar.lifemind.lifemindmessage.service.core.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindmessage.common.Result;
import thinkunderstar.lifemind.lifemindmessage.common.ResultCode;
import thinkunderstar.lifemind.lifemindmessage.dto.request.NotificationQueryRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.request.NotificationReadRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.response.NotificationResponse;
import thinkunderstar.lifemind.lifemindmessage.entity.Notification;
import thinkunderstar.lifemind.lifemindmessage.event.NotificationEvent;
import thinkunderstar.lifemind.lifemindmessage.exception.BusinessException;
import thinkunderstar.lifemind.lifemindmessage.service.core.NotificationService;
import thinkunderstar.lifemind.lifemindmessage.service.wrapper.NotificationWrapperService;
import thinkunderstar.lifemind.lifemindmessage.util.RedisTokenBucketLimiter;

import java.time.LocalDateTime;

@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    /**
     * 单接收人限流：桶容量 10（允许的突发次数），每秒补充 1 个令牌。
     * 挡住对同一用户的通知轰炸，同时给正常互动留足突发余量。
     */
    private static final long NOTIFICATION_USER_CAPACITY = 10L;
    private static final double NOTIFICATION_USER_RATE = 1.0;

    /** 标记已读限流：桶容量 10，每秒补充 1 个令牌，按用户限流防刷 */
    private static final long NOTIFICATION_READ_CAPACITY = 10L;
    private static final double NOTIFICATION_READ_RATE = 1.0;

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

        // ========== 3. 幂等去重 ==========
        // 必须放在限流之前。若顺序反过来，被去重挡掉的重复消息也会先扣掉一个
        // 限流令牌——同一条提醒重投几次，用户正常的通知额度就被吃光了，
        // 表现为「明明没收到几条通知，却提示发送过于频繁」。
        //
        // 这里的查询只是快速路径：两个消费者实例并发处理同一事件时都查不到，
        // 真正兜底的是 event_id 上的唯一索引（见下面的 catch）。
        if (notificationEvent.getEventId() != null && !notificationEvent.getEventId().isBlank()) {
            boolean processed = notificationWrapperService.count(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getEventId, notificationEvent.getEventId())) > 0;
            if (processed) {
                // 正常返回而不是抛异常：重复消息应当被 ACK 掉。
                // 抛异常会让 RocketMQ 一直重投，最终堆进死信队列，
                // 把「重复投递」变成「永远投不进去」。
                log.info("通知事件已处理过，跳过：eventId={}", notificationEvent.getEventId());
                return;
            }
        }

        // ========== 4. 按接收人限流 ==========
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

        // 老消息体里没有 needPopup（发布前已投递、还在队列里或重试中的），
        // 反序列化后为 null。这里显式归 0：null 是「没声明」，语义上等于「不弹」，
        // 不能让它落库后看起来像第三种状态
        if (notification.getNeedPopup() == null) {
            notification.setNeedPopup(0);
        }

        // 老消息体同样没有 eventId，保持 null（唯一索引允许多个 NULL），
        // 这类消息没有幂等保护，与上线前的行为一致
        try {
            //落库
            notificationWrapperService.save(notification);
        } catch (DuplicateKeyException e) {
            // 并发窗口：两个消费者实例同时通过了上面的查询，唯一索引只放进来一条。
            // 静默吞掉，等同于「已经处理过」——这一条消息的目的已经达成，
            // 让它 ACK 掉比让它重试到死信更有意义。
            log.info("通知事件并发重复，已被唯一索引拦截：eventId={}",
                    notificationEvent.getEventId());
        }
    }

    @Override
    public Result<IPage<NotificationResponse>> getNotificationPage(NotificationQueryRequest queryRequest,
                                                                   HttpServletRequest request) {

        // ========== 1. 参数校验 ==========
        if (queryRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }

        // ========== 2. 取当前登录用户 ==========
        // 只查网关注入的身份对应的用户的通知，前端传什么用户 ID 都不认；
        // 认证由网关完成，身份从 LifeMind-User-Id 请求头解析（与 HeaderAuthenticationFilter 一致），
        // 请求头缺失或格式非法说明请求没带 Token 或没走网关，按未登录处理
        String userIdHeader = request.getHeader("LifeMind-User-Id");
        if (userIdHeader == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        Long userId;
        try {
            userId = Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }

        // ========== 3. 组装分页查询 ==========
        // 页码、每页条数做兜底，防止前端传 0 或负数导致分页插件异常
        long pageNum = queryRequest.getPageNum() == null || queryRequest.getPageNum() <= 0
                ? 1 : queryRequest.getPageNum();
        long pageSize = queryRequest.getPageSize() == null || queryRequest.getPageSize() <= 0
                ? 10 : queryRequest.getPageSize();

        LambdaQueryWrapper<Notification> queryWrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                // 通知类型过滤，可选
                .eq(queryRequest.getType() != null && !queryRequest.getType().isBlank(),
                        Notification::getType, queryRequest.getType())
                // 已读状态过滤，可选
                .eq(queryRequest.getIsRead() != null,
                        Notification::getIsRead, queryRequest.getIsRead())
                // 弹窗标记过滤，可选：前端轮询用 isRead=0&needPopup=1 只取该弹的那几条
                .eq(queryRequest.getNeedPopup() != null,
                        Notification::getNeedPopup, queryRequest.getNeedPopup())
                // 新通知在前
                .orderByDesc(Notification::getCreateTime);

        // ========== 4. 分页查询并转换返回 ==========
        // 分页信息（total/current/size/pages）由 Page 自带给前端
        Page<Notification> page = notificationWrapperService.page(
                new Page<>(pageNum, pageSize), queryWrapper);

        IPage<NotificationResponse> pageResult = page.convert(
                notification -> BeanUtil.copyProperties(notification, NotificationResponse.class));

        return Result.success(pageResult);
    }

    @Override
    public Result<Void> markNotificationRead(NotificationReadRequest readRequest, HttpServletRequest request) {

        // ========== 1. 参数校验 ==========
        if (readRequest == null || readRequest.getId() == null || readRequest.getId() <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "通知ID不能为空");
        }

        // ========== 2. 取当前登录用户 ==========
        // 只允许标记属于自己的通知，身份从网关注入的 LifeMind-User-Id 请求头解析
        String userIdHeader = request.getHeader("LifeMind-User-Id");
        if (userIdHeader == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        Long userId;
        try {
            userId = Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }

        // ========== 3. 限流 ==========
        if (!rateLimiter.tryAcquireByUser(userId, NOTIFICATION_READ_CAPACITY, NOTIFICATION_READ_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }

        // ========== 4. 查询通知并校验归属 ==========
        // 不存在或不属于当前用户统一按"通知不存在"处理，避免暴露他人通知的存在性
        Notification notification = notificationWrapperService.getById(readRequest.getId());
        if (notification == null || !notification.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "通知不存在");
        }

        // ========== 5. 已读幂等：已是已读状态直接返回 ==========
        if (notification.getIsRead() != null && notification.getIsRead() == 1) {
            return Result.success();
        }

        // ========== 6. 标记已读 ==========
        notificationWrapperService.update(new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, readRequest.getId())
                .set(Notification::getIsRead, 1)
                .set(Notification::getReadTime, LocalDateTime.now()));

        return Result.success();
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

        // 是否弹窗：可空（按 0 处理），不为空时只允许 0-否 1-是。
        // 用 0/1 而不是布尔，与 is_read、deleted 保持一致，也方便以后扩展成多值
        if (event.getNeedPopup() != null && (event.getNeedPopup() < 0 || event.getNeedPopup() > 1)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "弹窗标记不合法");
        }

        // 事件幂等键：可空；非空时长度不能超过列宽（64），
        // 否则 Insert 会被 MySQL 以「Data too long」拒绝，而那条消息会在
        // 重试 16 次后进死信——不如在这里就明确报参数错误
        if (event.getEventId() != null && event.getEventId().length() > 64) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "事件幂等键长度超出限制");
        }

        // 扩展数据：可空，长度上限 1000
        if (event.getExtra() != null && event.getExtra().length() > 1000) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "扩展数据长度超出限制");
        }
    }
}
