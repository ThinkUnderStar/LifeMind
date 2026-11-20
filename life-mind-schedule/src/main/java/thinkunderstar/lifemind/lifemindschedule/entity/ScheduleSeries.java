package thinkunderstar.lifemind.lifemindschedule.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 重复系列：一条规则，展开成 {@code schedule_item} 里的多条实例。
 *
 * <p>为什么模板要独立成表：{@code schedule_item} 的语义是「一行一个具体时刻」，
 * 而模板没有具体时刻。塞进去就只能靠 status 加一个特殊值来表达「这是模板」，
 * 结果列表、概览、按日统计、逾期查询每一处都要额外过滤一次。
 *
 * <p>规则必须有终点（{@code countLimit} 或 {@code untilDate} 至少一个）：
 * 允许无限重复就必须有后台任务滚动补充实例，那个任务挂了是静默失败，
 * 用户在第 N+1 天就看不到日程了。要求有终点，创建时一次展开就结束。
 *
 * <p>时间字段约束（与建表 SQL 注释一致）：
 * <ul>
 *     <li>{@code itemType=schedule}：{@code baseTime} 是每次的开始时刻，{@code durationMinutes} 推出结束时刻</li>
 *     <li>{@code itemType=todo}：{@code baseTime} 是每次的截止时刻，{@code durationMinutes} 无意义</li>
 * </ul>
 */
@Data
@TableName("schedule_series")
public class ScheduleSeries {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 归属用户ID：所有查询都必须带这个条件
     */
    private Long userId;

    /**
     * 展开成什么：schedule-日程 todo-待办
     */
    private String itemType;

    /**
     * 标题
     */
    private String title;

    /**
     * 详细内容 / 备注
     */
    private String content;

    /**
     * 地点，仅日程使用
     */
    private String location;

    /**
     * 优先级：0-低 1-中 2-高
     */
    private Integer priority;

    /**
     * 是否全天：0-否 1-是（仅日程有意义）
     */
    private Integer allDay;

    /**
     * 标签，逗号分隔
     */
    private String tags;

    /**
     * 频率：DAILY-每天 WEEKLY-每周 MONTHLY-每月
     */
    private String freq;

    /**
     * 间隔：每 N 天/周/月。
     *
     * <p>单独一列而不是塞进 freq（如 {@code WEEKLY:2}）：拆开之后校验、展示、
     * 以及前端做「每 [2] [周]」这种表单都不用解析字符串。
     */
    private Integer intervalVal;

    /**
     * 星期几：{@code MO,WE,FR}，逗号分隔，仅 WEEKLY 使用。
     *
     * <p>为空表示取锚点当天是星期几。用 {@code java.time.DayOfWeek#name()} 的前两位，
     * 与 RFC5545 的写法一致，将来要换成标准 rrule 字符串时不用重新解释语义。
     */
    private String byDay;

    /**
     * 终止日期（含当天）。与 {@link #countLimit} 至少填一个。
     *
     * <p>用 {@code LocalDate} 而不是 {@code LocalDateTime}：用户说的是「到年底」，
     * 不该被迫指定 23:59:59。展开时按「日期不晚于该日」判断。
     */
    private LocalDate untilDate;

    /**
     * 总次数上限（含第一次），1~100。与 {@link #untilDate} 至少填一个。
     */
    private Integer countLimit;

    /**
     * 锚点时刻：日程取开始时间，待办取截止时间。
     *
     * <p>不用 {@code start_time} 命名：待办存的是截止时间，叫 start 会误导。
     */
    private LocalDateTime baseTime;

    /**
     * 每次时长（分钟），仅日程；空则展开出的实例 {@code endTime} 也为空。
     *
     * <p>存「时长」而不是让每条实例自己带结束时间：重复规则里「每次两小时」是常量，
     * 存成时长后改锚点不会让历史实例的时长跟着错。
     */
    private Integer durationMinutes;

    /**
     * 提醒偏移，逗号分隔，如 {@code "0,30,1440"}；空表示不提醒。
     *
     * <p>存字符串而不是关联表：它只是「展开时给每条实例建哪几个提醒」的参数，
     * 不需要被 SQL 查询，也不需要单独增删改。真正一行一个提醒的是 {@code schedule_reminder}。
     */
    private String remindOffsets;

    /**
     * 状态：0-生效 1-已结束。
     *
     * <p>没有「已删除」这一档——删除就是 {@code deleted=1}，两件事不要混。
     * 「已结束」只在次数用完或过了终止日期且实例全部过期后由服务端标记，
     * 不影响查询：实例本身的行为准。
     */
    private Integer status;

    /**
     * 幂等键：同一用户下相同值视为同一次创建。
     *
     * <p>agent 会在超时后重试，没有这个键「每周三健身」会被建两套系列（24 条实例）。
     */
    private String requestId;

    /**
     * 数据来源：{@code web}-前端手写 {@code agent}-智能体代操作。纯审计字段，不参与鉴权。
     */
    private String source;

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
     * 逻辑删除：0-未删除 1-已删除
     */
    @TableLogic
    private Integer deleted;
}
