package thinkunderstar.lifemind.lifemindschedule.handler;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import thinkunderstar.lifemind.lifemindschedule.common.Result;
import thinkunderstar.lifemind.lifemindschedule.common.ResultCode;
import thinkunderstar.lifemind.lifemindschedule.exception.BusinessException;

import java.util.List;

/**
 * 全局异常处理器。
 *
 * <p>把控制器链路里抛出的异常统一转成 {@link Result}，保证前端拿到的结构一致。
 *
 * <p><b>为什么返回 {@link ResponseEntity} 而不是裸的 {@code Result}</b>：
 * 裸返回时所有异常都是 HTTP 200，状态码只写在 body 的 {@code code} 里。
 * 前端会读 body，问题不大；但 agent 的工具封装通常先看 HTTP 状态，
 * 失败被当成成功后，模型会继续往下走并告诉用户「已删除」——实际什么都没发生。
 * 所以这里让 HTTP 状态码与 body 里的 code 保持一致，两层都能判断。
 *
 * <p>与 account / message 的差异仅此一项，body 结构逐字不变，前端不需要改解析逻辑。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：参数校验失败、资源不存在、越权等主动抛出的异常，状态码原样透传。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(toHttpStatus(e.getCode()))
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 请求体反序列化失败：JSON 结构不合法、字段类型不匹配、时间格式不认识。
     *
     * <p>尽量把「哪个字段、什么值」带进错误信息。agent 拿到「请求参数格式不正确」
     * 只能重试同样的内容；拿到「startTime：时间格式无法识别…」才有机会改对。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        String detail = describeField(e);
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(Result.error(ResultCode.PARAM_ERROR, detail));
    }

    /**
     * 请求体校验失败：DTO 上 {@code @Valid} 不通过。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = firstFieldError(e.getBindingResult().getFieldErrors());
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.badRequest()
                .body(Result.error(ResultCode.PARAM_ERROR, message));
    }

    /**
     * 查询参数校验失败：GET 请求 {@code @ModelAttribute} + {@code @Valid} 不通过。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        String message = firstFieldError(e.getBindingResult().getFieldErrors());
        log.warn("查询参数校验失败: {}", message);
        return ResponseEntity.badRequest()
                .body(Result.error(ResultCode.PARAM_ERROR, message));
    }

    /**
     * 必填查询参数缺失。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数: {}", e.getParameterName());
        return ResponseEntity.badRequest()
                .body(Result.error(ResultCode.PARAM_ERROR, "缺少必填参数：" + e.getParameterName()));
    }

    /**
     * 参数类型不匹配：最常见的是路径上写了非数字的 id。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不正确: name={}, value={}", e.getName(), e.getValue());
        return ResponseEntity.badRequest()
                .body(Result.error(ResultCode.PARAM_ERROR, "参数类型不正确：" + e.getName()));
    }

    /**
     * 兜底：记完整堆栈，只向前端返回通用提示，避免泄漏内部细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ResultCode.ERROR));
    }

    /**
     * body 里的业务码映射到 HTTP 状态码。
     *
     * <p>只映射本服务实际会用的几个；未知码统一按 400 处理——
     * 未知说明是调用方传错了什么，不是服务端崩了，500 会误导排查方向。
     */
    private HttpStatus toHttpStatus(Integer code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    /**
     * 取第一条字段错误的中文提示。
     */
    private String firstFieldError(List<FieldError> fieldErrors) {
        return fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(ResultCode.PARAM_ERROR.getMessage());
    }

    /**
     * 从反序列化异常里挖出「字段名 + 具体原因」。
     *
     * <p>Jackson 把真正的错误包在 cause 里，直接把外层 message 抛给调用方
     * 会得到一大段带 Java 类名的英文，既读不懂也没法定位。
     */
    private String describeField(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();
        if (cause instanceof InvalidFormatException invalidFormat) {
            String field = fieldNameOf(invalidFormat);
            String reason = invalidFormat.getOriginalMessage();
            // 自定义 deserializer 抛的 weirdStringException 已经把中文原因放在 originalMessage 里
            if (StringUtils.hasText(field)) {
                return field + "：" + reason;
            }
            return reason;
        }
        if (cause instanceof JsonMappingException mappingException) {
            String field = fieldNameOf(mappingException);
            if (StringUtils.hasText(field)) {
                return field + "：字段类型不正确";
            }
        }
        return ResultCode.PARAM_ERROR.getMessage() + "：请求体不是合法的 JSON";
    }

    /**
     * 取异常路径里的字段名，如 {@code remindOffsets[0]}。
     */
    private String fieldNameOf(JsonMappingException e) {
        if (e.getPath() == null || e.getPath().isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        e.getPath().forEach(reference -> {
            if (reference.getFieldName() != null) {
                if (!builder.isEmpty()) {
                    builder.append('.');
                }
                builder.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                builder.append('[').append(reference.getIndex()).append(']');
            }
        });
        return builder.isEmpty() ? null : builder.toString();
    }
}
