package thinkunderstar.lifemind.lifemindschedule.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提醒表：一行 = 一个提醒时刻。
 *
 * <p>一个事项可以有多个提醒（提前一天、提前半小时），所以不能只在事项上放一个时间字段。
 *
 * <p>{@code offsetMinutes} 只用于展示和重算：用户改了事项时间后，服务端按它重新算
 * {@code remindTime}；扫描任务只认 {@code remindTime} 这个绝对时刻。
 *
 * <p>状态机：0 待发送 → 1 已发送 / 2 已取消 / 3 发送失败。
 * 没有「已发送 → 待发送」的路径，宁可漏一条也不重复轰炸用户。
 */
@Data
@TableName("schedule_reminder")
public class ScheduleReminder {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 归属用户ID（冗余自事项：扫描时直接用来填通知接收人，不必回查事项表）
     */
    private Long userId;

    /**
     * 关联事项ID
     */
    private Long itemId;

    /**
     * 实际触发时刻 = 事项时间 - offsetMinutes
     */
    private LocalDateTime remindTime;

    /**
     * 提前分钟数：0-准时，1440-提前一天
     */
    private Integer offsetMinutes;

    /**
     * 发送渠道：inbox-站内信（预留 sms / email）
     */
    private String channel;

    /**
     * 状态：0-待发送 1-已发送 2-已取消 3-发送失败
     */
    private Integer status;

    /**
     * 投递失败重试次数
     */
    private Integer retryCount;

    /**
     * 实际投递成功时间
     */
    private LocalDateTime sentTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除
     */
    @TableLogic
    private Integer deleted;
}
