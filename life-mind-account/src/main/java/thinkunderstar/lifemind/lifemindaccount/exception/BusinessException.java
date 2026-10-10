package thinkunderstar.lifemind.lifemindaccount.exception;

import lombok.Getter;
import thinkunderstar.lifemind.lifemindaccount.common.Result;
import thinkunderstar.lifemind.lifemindaccount.common.ResultCode;

import java.io.Serial;

/**
 * 业务异常。
 *
 * <p>用于在业务代码中主动中断流程（参数校验失败、限流命中、权限不足等），
 * 由 {@code GlobalExceptionHandler} 统一捕获并转换成 {@link Result} 返回给前端。
 */
@Getter
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务状态码，取自 {@link ResultCode}
     */
    private final Integer code;

    /**
     * 使用 {@link ResultCode} 的默认提示信息
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    /**
     * 使用 {@link ResultCode} 的状态码，自定义提示信息
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    /**
     * 完全自定义状态码与提示信息
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
