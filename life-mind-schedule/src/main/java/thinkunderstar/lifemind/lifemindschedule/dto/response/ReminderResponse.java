package thinkunderstar.lifemind.lifemindschedule.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提醒的展示对象。
 *
 * <p>把 {@code status} 一起返回：前端要区分「还会响」和「已经响过」，
 * 而不是把所有提醒都渲染成待触发。
 */
@Data
public class ReminderResponse {

    /**
     * 提醒ID
     */
    private Long id;

    /**
     * 关联事项ID
     */
    private Long itemId;

    /**
     * 触发时刻
     */
    private LocalDateTime remindTime;

    /**
     * 提前分钟数：0-准时
     */
    private Integer offsetMinutes;

    /**
     * 发送渠道
     */
    private String channel;

    /**
     * 状态：0-待发送 1-已发送 2-已取消 3-发送失败
     */
    private Integer status;

    /**
     * 实际投递成功时间
     */
    private LocalDateTime sentTime;
}
