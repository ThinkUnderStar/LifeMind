package thinkunderstar.lifemind.lifemindaccount.common;

import lombok.Getter;

/**
 * 统一响应状态码
 */
@Getter
public enum ResultCode {

    /**
     * 操作成功
     */
    SUCCESS(200, "操作成功"),

    /**
     * 操作失败
     */
    ERROR(500, "操作失败"),

    /**
     * 参数校验失败
     */
    PARAM_ERROR(400, "参数校验失败"),

    /**
     * 未登录或登录已过期
     */
    UNAUTHORIZED(401, "未登录或登录已过期"),

    /**
     * 没有操作权限
     */
    FORBIDDEN(403, "没有操作权限"),

    /**
     * 请求的资源不存在
     */
    NOT_FOUND(404, "请求的资源不存在");

    /**
     * 状态码
     */
    private final Integer code;

    /**
     * 提示信息
     */
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
