package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建重复系列。
 *
 * <p>调用一次会展开成多条事项，所以「重复到什么时候」必须说清楚：
 * {@code countLimit} 与 {@code untilDate} 至少填一个。允许无限重复就得有后台任务
 * 滚动补实例，那个任务挂了是静默失败——用户在第 N+1 天突然看不到日程。
 * 所以这里宁可报错让调用方补一个终点。
 *
 * <p>{@code baseTime} 对两种类型的含义不同，这是本接口最容易用错的地方：
 * 日程填开始时间，待办填截止时间。展开出来的每条实例都按它 ± 固定间隔。
 */
@Data
public class SeriesCreateRequest {

    /**
     * 展开成什么：schedule-日程 todo-待办
     */
    @NotBlank(message = "事项类型不能为空")
    @Pattern(regexp = "schedule|todo", message = "事项类型只能是 schedule 或 todo")
    private String itemType;

    /**
     * 标题
     */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题不能超过 200 个字")
    private String title;

    /**
     * 详细内容 / 备注
     */
    private String content;

    /**
     * 地点，仅日程使用
     */
    @Size(max = 255, message = "地点不能超过 255 个字")
    private String location;

    /**
     * 优先级：0-低 1-中 2-高，默认中
     */
    @Min(value = 0, message = "优先级只能是 0、1、2")
    @Max(value = 2, message = "优先级只能是 0、1、2")
    private Integer priority;

    /**
     * 是否全天：0-否 1-是，默认 0
     */
    @Min(value = 0, message = "全天标记只能是 0 或 1")
    @Max(value = 1, message = "全天标记只能是 0 或 1")
    private Integer allDay;

    /**
     * 标签，逗号分隔
     */
    @Size(max = 255, message = "标签总长度不能超过 255 个字")
    private String tags;

    /**
     * 频率：DAILY-每天 WEEKLY-每周 MONTHLY-每月
     */
    @NotBlank(message = "重复频率不能为空")
    @Pattern(regexp = "DAILY|WEEKLY|MONTHLY", message = "频率只能是 DAILY、WEEKLY 或 MONTHLY")
    private String freq;

    /**
     * 间隔：每 N 天 / 周 / 月，默认 1。
     *
     * <p>上限 30：再大就不是「重复」而是一条普通事项了，展开出来也没几条，
     * 用户完全可以直接建单条。
     */
    @Min(value = 1, message = "间隔至少是 1")
    @Max(value = 30, message = "间隔最多 30")
    private Integer interval;

    /**
     * 星期几，取值 MO/TU/WE/TH/FR/SA/SU；仅 {@code freq=WEEKLY} 可用。
     *
     * <p>不填表示「和锚点是同一个星期几」。其他频率传了会直接报错，而不是静默忽略——
     * 静默忽略的表现是「接口返回成功，但排出来的日子和用户说的不一样」。
     */
    @Size(max = 7, message = "一个星期最多 7 天")
    private List<@Pattern(regexp = "MO|TU|WE|TH|FR|SA|SU",
            message = "星期几只能取 MO、TU、WE、TH、FR、SA、SU") String> byDay;

    /**
     * 终止日期（含当天）。与 countLimit 至少填一个
     */
    private LocalDate untilDate;

    /**
     * 总次数上限（含第一次），1~100。与 untilDate 至少填一个
     */
    @Min(value = 1, message = "次数至少是 1")
    @Max(value = 100, message = "次数最多 100，更长的重复请分段创建")
    private Integer countLimit;

    /**
     * 锚点时刻：日程填开始时间，待办填截止时间。必填
     */
    @NotNull(message = "锚点时刻不能为空：日程填开始时间，待办填截止时间")
    private LocalDateTime baseTime;

    /**
     * 每次时长（分钟），仅日程。用于推算每条实例的结束时间；不填则结束时时间为空
     */
    @Min(value = 0, message = "时长不能为负数")
    @Max(value = 10080, message = "单次时长最多 7 天")
    private Integer durationMinutes;

    /**
     * 提醒偏移（提前多少分钟）：0-准时，30-提前半小时，1440-提前一天
     */
    @Valid
    @Size(max = 10, message = "一个系列最多设置 10 个提醒")
    private List<@Min(value = 0, message = "提醒偏移不能为负数")
                 @Max(value = 10080, message = "提醒最多提前 7 天") Integer> remindOffsets;

    /**
     * 幂等键：同一用户下相同值视为同一次创建，重试不会建出两套系列
     */
    @Size(max = 64, message = "幂等键不能超过 64 个字符")
    private String requestId;
}
