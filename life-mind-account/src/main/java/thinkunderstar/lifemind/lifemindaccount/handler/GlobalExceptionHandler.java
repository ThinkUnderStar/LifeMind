package thinkunderstar.lifemind.lifemindaccount.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import thinkunderstar.lifemind.lifemindaccount.common.BusinessException;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.common.ResultCode;

/**
 * 全局异常处理器。
 *
 * <p>把控制器链路里抛出的异常统一转换成 {@link Result}，保证前端拿到的响应结构一致。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：参数校验失败、限流命中等主动抛出的异常，
     * 状态码与提示信息由异常携带，原样返回给前端。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 请求体反序列化失败：JSON 结构不合法、字段类型不匹配等，
     * 在进入 controller 方法之前就抛出，统一按参数错误处理。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "请求参数格式不正确");
    }

    /**
     * 参数校验失败：DTO 上使用 {@code @Valid} / {@code @Validated} 时，
     * 校验不通过会抛出该异常，取第一条字段错误信息作为提示。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(ResultCode.PARAM_ERROR.getMessage());
        log.warn("参数校验失败: {}", message);
        return Result.error(ResultCode.PARAM_ERROR, message);
    }

    /**
     * 兜底处理：未被上面命中的异常都走到这里，
     * 记完整堆栈但只向前端返回通用提示，避免泄漏内部细节。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(ResultCode.ERROR);
    }
}
