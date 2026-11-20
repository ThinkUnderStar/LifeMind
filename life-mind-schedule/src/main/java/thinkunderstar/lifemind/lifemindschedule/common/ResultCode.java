package thinkunderstar.lifemind.lifemindschedule.common;

import lombok.Getter;

/**
 * 统一响应状态码。
 *
 * <p>与 account / message 服务保持同一套数字，前端只按 {@code code} 判断成功与否，
 * 不必按服务写不同的分支。业务上只用得到这几个：本服务没有上传下载，也不做限流
 * （限流在网关按 IP 做，本服务在内网）。
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
     * 未登录或登录已过期：请求头缺失 / 非法，说明请求没走网关
     */
    UNAUTHORIZED(401, "未登录或登录已过期"),

    /**
     * 没有操作权限：比如想改别人的事项
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
