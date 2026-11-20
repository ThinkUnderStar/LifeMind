package thinkunderstar.lifemind.lifemindschedule.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import thinkunderstar.lifemind.lifemindschedule.common.ResultCode;
import thinkunderstar.lifemind.lifemindschedule.exception.BusinessException;
import thinkunderstar.lifemind.lifemindschedule.security.LifeMindUserDetails;

/**
 * 当前登录用户。
 *
 * <p>业务层统一从这里取 userId，不往 Service 里传 HttpServletRequest：
 * 传 request 会让 Service 依赖 Web 层，单测时得造一个假 request 才能跑。
 *
 * <p>{@code HeaderAuthenticationFilter} 已经保证正常请求一定有身份，
 * 这里再判一次是兜底——万一将来某个路径被加进白名单，Service 也不会拿着
 * null 用户去查库（那样会查出空列表，看起来像「没有数据」，其实是身份丢了）。
 */
public final class UserContext {

    private UserContext() {
    }

    /**
     * 取当前登录用户ID。
     *
     * @return 用户ID，保证非 null
     * @throws BusinessException 身份缺失（401），说明请求没走网关或请求头被剥掉了
     */
    public static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LifeMindUserDetails details)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = details.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 取本次请求的调用方标识：{@code web} 或 {@code agent}。
     *
     * <p>只给「新建数据时打来源标记」用。身份缺失时直接按 web 返回而不是抛异常：
     * 来源是审计信息，缺了它不该让整个业务请求失败——那会变成
     * 「agent 少传一个头，用户连日程都建不了」。
     *
     * @return 调用方标识，保证非 null
     */
    public static String currentClient() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LifeMindUserDetails details)) {
            return "web";
        }
        String client = details.getClient();
        return client == null || client.isBlank() ? "web" : client;
    }
}
