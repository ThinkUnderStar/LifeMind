package thinkunderstar.lifemind.lifemindmessage.service.core.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.minio.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import thinkunderstar.lifemind.lifemindmessage.common.Result;
import thinkunderstar.lifemind.lifemindmessage.common.ResultCode;
import thinkunderstar.lifemind.lifemindmessage.dto.request.FeedbackProcessRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.request.FeedbackQueryRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.request.FeedbackSubmitRequest;
import thinkunderstar.lifemind.lifemindmessage.dto.response.FeedbackResponse;
import thinkunderstar.lifemind.lifemindmessage.entity.Feedback;
import thinkunderstar.lifemind.lifemindmessage.event.NotificationEvent;
import thinkunderstar.lifemind.lifemindmessage.exception.BusinessException;
import thinkunderstar.lifemind.lifemindmessage.service.core.FeedbackService;
import thinkunderstar.lifemind.lifemindmessage.service.core.NotificationService;
import thinkunderstar.lifemind.lifemindmessage.service.wrapper.FeedbackWrapperService;
import thinkunderstar.lifemind.lifemindmessage.util.RedisTokenBucketLimiter;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 反馈服务实现。
 *
 * <p>反馈与通知联动：提交反馈后给提交人发一条"反馈已收到"通知；
 * 处理反馈后给提交人发一条"反馈已处理"通知。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackServiceImpl implements FeedbackService {

    private final FeedbackWrapperService feedbackWrapperService;
    private final NotificationService notificationService;
    private final RedisTokenBucketLimiter rateLimiter;

    /** 提交反馈限流：桶容量 10（允许的突发次数），每秒补充 1 个令牌，按用户限流防刷 */
    private static final long FEEDBACK_SUBMIT_CAPACITY = 10L;
    private static final double FEEDBACK_SUBMIT_RATE = 1.0;

    /** 处理反馈限流：桶容量 20（允许的突发次数），每秒补充 1 个令牌，按管理员限流 */
    private static final long FEEDBACK_PROCESS_CAPACITY = 20L;
    private static final double FEEDBACK_PROCESS_RATE = 1.0;

    /** 查询反馈限流：桶容量 30（允许的突发次数），每秒补充 1 个令牌，按管理员限流 */
    private static final long FEEDBACK_QUERY_CAPACITY = 30L;
    private static final double FEEDBACK_QUERY_RATE = 1.0;

    /** 反馈类型合法范围：1-功能建议 2-Bug反馈 3-体验问题 4-其他 */
    private static final int[] FEEDBACK_TYPES = {1, 2, 3, 4};

    /** 处理状态合法范围（提交时固定为待处理，处理时可流转到 1/2/3） */
    private static final int[] FEEDBACK_STATUS = {1, 2, 3};

    /** 反馈截图：允许的常见图片格式 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /** 反馈截图：最大体积 5MB */
    private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;

    /** 公共读桶 */
    private static final String PUBLIC_BUCKET = "life-mind-public";

    /** 公共读桶策略：允许匿名 s3:GetObject，%s 为桶名 */
    private static final String BUCKET_PUBLIC_READ_POLICY =
            "{\n" +
            "  \"Version\": \"2012-10-17\",\n" +
            "  \"Statement\": [\n" +
            "    {\n" +
            "      \"Effect\": \"Allow\",\n" +
            "      \"Principal\": {\"AWS\": [\"*\"]},\n" +
            "      \"Action\": [\"s3:GetObject\"],\n" +
            "      \"Resource\": [\"arn:aws:s3:::%s/*\"]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private final MinioClient minioClient;

    @Override
    public Result<Void> submitFeedback(FeedbackSubmitRequest submitRequest, MultipartFile file , HttpServletRequest request) {

        // ========== 1. 参数校验 ==========
        if (submitRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }
        Integer type = submitRequest.getType();
        if (type == null || !contains(FEEDBACK_TYPES, type)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "反馈类型不合法");
        }
        String content = submitRequest.getContent();
        if (content == null || content.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "反馈内容不能为空");
        }
        if (content.length() > 1000) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "反馈内容不能超过1000字");
        }

        // 反馈截图校验（可选）：文件为空则跳过，否则校验图片格式与体积
        if (file != null && !file.isEmpty()) {
            validateFeedbackImage(file);
        }

        // ========== 2. 取当前登录用户 ==========
        // 用户身份由网关注入的 LifeMind-User-Id 请求头解析（与 HeaderAuthenticationFilter 一致），
        // 请求头缺失或格式非法说明请求没带 Token 或没走网关，按未登录处理
        Long userId = resolveUserId(request);

        // ========== 3. 限流 ==========
        // 按用户限流，防止刷反馈拖垮系统
        if (!rateLimiter.tryAcquireByUser(userId, FEEDBACK_SUBMIT_CAPACITY, FEEDBACK_SUBMIT_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "提交反馈过于频繁，请稍后再试");
        }

        // 4. 如果有截图的话将截图存入minio中
        String objectName = null;
        if (file != null && !file.isEmpty()) {
            try (InputStream inputStream = file.getInputStream()) {
                String fileType = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf("."));
                String fileName = UUID.randomUUID() + fileType;

                // 确保公共读桶存在且持有公开读策略（幂等）
                ensurePublicBucket(PUBLIC_BUCKET);

                objectName = "users/" + userId + "/feedback-images/" + fileName;

                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(PUBLIC_BUCKET)
                                .contentType(file.getContentType())
                                .stream(inputStream, file.getSize(), -1)
                                .object(objectName)
                                .build()
                );
            } catch (Exception e) {
                log.error("反馈截图上传失败, userId={}", userId, e);
                throw new BusinessException(ResultCode.UPLOAD_ERROR, "反馈截图上传失败");
            }
        }

        // ========== 5. 组装并保存反馈 ==========
        Feedback feedback = new Feedback();
        feedback.setUserId(userId);
        feedback.setType(type);
        feedback.setContent(content);
        // 没传截图时 images 存空，避免拼出 "/minio/life-mind-public/null"
        feedback.setImages(file != null && !file.isEmpty()
                ? "/minio/" + PUBLIC_BUCKET + "/" + objectName
                : null);
        feedback.setContact(submitRequest.getContact());
        feedback.setStatus(0); // 初始待处理
        feedbackWrapperService.save(feedback);

        // ========== 6. 发送"反馈已收到"通知 ==========
        NotificationEvent event = new NotificationEvent();
        event.setUserId(userId);
        event.setType("feedback");
        event.setTitle("反馈已收到");
        event.setContent("您的反馈已收到，我们会尽快处理。反馈内容：" + content);
        event.setBizType("feedback");
        event.setBizId(String.valueOf(feedback.getId()));
        notificationService.sendNotification(event);

        return Result.success();
    }

    /**
     * 校验反馈截图是否为合法的静态图片。
     *
     * <p>校验项：文件名非空、扩展名为常见图片格式（jpg/jpeg/png/gif/webp/bmp）、
     * 体积不超过 5MB。调用方需保证文件非空。
     *
     * @param file 反馈截图文件
     */
    private void validateFeedbackImage(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "反馈截图文件名不能为空");
        }
        String extension = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        if (!IMAGE_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "反馈截图仅支持常见图片格式（jpg/jpeg/png/gif/webp/bmp）");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "反馈截图不能超过5MB");
        }
    }

    /**
     * 确保公共读桶存在且持有公开读策略（幂等）。
     *
     * <p>桶不存在则创建并套上匿名可读策略；桶已存在也重新设一次策略，
     * 防止桶是早先手动建成的私有桶，导致上传成功但匿名读不了。
     */
    private void ensurePublicBucket(String bucketName) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
        minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                .bucket(bucketName)
                .config(String.format(BUCKET_PUBLIC_READ_POLICY, bucketName))
                .build());
    }

    @Override
    public Result<IPage<FeedbackResponse>> getFeedbackPage(FeedbackQueryRequest queryRequest,
                                                           HttpServletRequest request) {

        // ========== 1. 参数校验 ==========
        if (queryRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }

        // ========== 2. 限流 ==========
        // 管理员查询反馈涉及全量 DB 分页查询，按管理员限流，防止高频调用拖垮数据库连接池
        Long adminId = resolveUserId(request);
        if (!rateLimiter.tryAcquireByUser(adminId, FEEDBACK_QUERY_CAPACITY, FEEDBACK_QUERY_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }

        // ========== 3. 组装查询条件并分页查询（管理员视角：查全部反馈） ==========
        // 管理员可以查看所有用户提交的反馈，不按当前登录用户过滤；
        // 传 type/status 则按类别/处理状态筛选，不传则查看全部
        LambdaQueryWrapper<Feedback> wrapper = new LambdaQueryWrapper<Feedback>()
                .eq(queryRequest.getType() != null, Feedback::getType, queryRequest.getType())
                .eq(queryRequest.getStatus() != null, Feedback::getStatus, queryRequest.getStatus())
                .orderByDesc(Feedback::getCreateTime);

        long pageNum = queryRequest.getPageNum() == null ? 1 : queryRequest.getPageNum();
        long pageSize = queryRequest.getPageSize() == null ? 10 : queryRequest.getPageSize();
        Page<Feedback> page = feedbackWrapperService.page(new Page<>(pageNum, pageSize), wrapper);

        // ========== 4. 实体转 VO，分页信息由 Page 携带 ==========
        IPage<FeedbackResponse> resultPage = page.convert(feedback ->
                BeanUtil.copyProperties(feedback, FeedbackResponse.class));
        return Result.success(resultPage);
    }

    @Override
    public Result<Void> processFeedback(FeedbackProcessRequest processRequest, HttpServletRequest request) {

        // ========== 1. 参数校验 ==========
        if (processRequest == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求参数不能为空");
        }
        Long id = processRequest.getId();
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "反馈ID不能为空");
        }
        Integer status = processRequest.getStatus();
        if (status == null || !contains(FEEDBACK_STATUS, status)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "处理状态不合法");
        }
        String reply = processRequest.getReply();
        if (reply == null || reply.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "回复内容不能为空");
        }
        if (reply.length() > 1000) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "回复内容不能超过1000字");
        }

        // ========== 2. 取当前操作人（管理员） ==========
        Long replyUserId = resolveUserId(request);

        // ========== 3. 限流 ==========
        // 按管理员限流，防止批量发接口被打爆
        if (!rateLimiter.tryAcquireByUser(replyUserId, FEEDBACK_PROCESS_CAPACITY, FEEDBACK_PROCESS_RATE)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }

        // ========== 4. 查询反馈是否存在 ==========
        Feedback feedback = feedbackWrapperService.getById(id);
        if (feedback == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "反馈不存在");
        }

        // ========== 5. 更新反馈处理结果 ==========
        feedback.setStatus(status);
        feedback.setReply(reply);
        feedback.setReplyUserId(replyUserId);
        feedback.setReplyTime(LocalDateTime.now());
        feedbackWrapperService.updateById(feedback);

        // ========== 6. 发送"反馈已处理"通知 ==========
        NotificationEvent event = new NotificationEvent();
        event.setUserId(feedback.getUserId());
        event.setType("feedback");
        event.setTitle("反馈已处理");
        event.setContent("您的反馈已处理，回复：" + reply);
        event.setBizType("feedback");
        event.setBizId(String.valueOf(feedback.getId()));
        notificationService.sendNotification(event);

        return Result.success();
    }

    /**
     * 从网关注入的 {@code LifeMind-User-Id} 请求头解析当前用户 ID。
     *
     * <p>请求头缺失或格式非法说明请求没带 Token 或没走网关，按未登录处理。
     *
     * @param request 当前请求
     * @return 当前登录用户 ID
     */
    private Long resolveUserId(HttpServletRequest request) {
        String userIdHeader = request.getHeader("LifeMind-User-Id");
        if (userIdHeader == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        try {
            Long userId = Long.parseLong(userIdHeader);
            if (userId > 0) {
                return userId;
            }
        } catch (NumberFormatException ignored) {
            // 请求头内容不是数字，按未登录处理
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
    }

    /**
     * 判断目标值是否在允许的取值集合中。
     */
    private boolean contains(int[] array, int target) {
        for (int value : array) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }
}
