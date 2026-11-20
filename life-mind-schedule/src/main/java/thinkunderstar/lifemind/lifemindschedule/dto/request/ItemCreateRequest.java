package thinkunderstar.lifemind.lifemindschedule.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建事项（日程 / 待办）。
 *
 * <p>不含 userId：身份只认网关注入的 {@code LifeMind-User-Id}，
 * 前端传什么都不认——否则谁都能往别人日程里塞事。
 *
 * <p>时间字段的必填规则在 Service 层按 {@code itemType} 判断，不在这里用分组校验：
 * 「日程必须有开始时间」是业务规则，不是格式规则，报错文案也该由业务层给。
 *
 * <p>时间格式用 Jackson 默认的 ISO-8601：{@code 2026-11-20T09:30:00}。
 */
@Data
public class ItemCreateRequest {

    /**
     * 事项类型：schedule-日程 todo-待办
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
     * 开始时间：日程必填
     */
    private LocalDateTime startTime;

    /**
     * 结束时间，可空；传了就必须晚于开始时间
     */
    private LocalDateTime endTime;

    /**
     * 截止时间：待办使用
     */
    private LocalDateTime dueTime;

    /**
     * 是否全天：0-否 1-是，默认 0
     */
    @Min(value = 0, message = "全天标记只能是 0 或 1")
    @Max(value = 1, message = "全天标记只能是 0 或 1")
    private Integer allDay;

    /**
     * 优先级：0-低 1-中 2-高，默认 1
     */
    @Min(value = 0, message = "优先级只能是 0、1、2")
    @Max(value = 2, message = "优先级只能是 0、1、2")
    private Integer priority;

    /**
     * 标签，逗号分隔
     */
    @Size(max = 255, message = "标签总长度不能超过 255 个字")
    private String tags;

    /**
     * 幂等键：同一用户下重复提交同一个值，只会建一条，第二次返回第一次的记录。
     *
     * <p>可选。前端手写场景可以不传；agent 必须传——它的网络重试、图重跑、
     * 用户说「再来一次」都会重复发同一个请求，没有这个键就会建出重复日程。
     *
     * <p>建议由调用方生成 UUID 或「会话ID-轮次-序号」这类稳定值，
     * 不要用标题+时间拼——用户可能真的想建两条一样的。
     */
    @Size(max = 64, message = "幂等键不能超过 64 个字符")
    private String requestId;

    /**
     * 提醒偏移（提前多少分钟）：0-准时，30-提前半小时，1440-提前一天。
     *
     * <p>不传或空列表表示不提醒。上限 7 天（10080 分钟）——
     * 再早的提醒对生活事项没有意义，只会让扫描表里堆长期不触发的行。
     */
    @Valid
    @Size(max = 10, message = "一个事项最多设置 10 个提醒")
    private List<@Min(value = 0, message = "提醒偏移不能为负数")
                 @Max(value = 10080, message = "提醒最多提前 7 天") Integer> remindOffsets;
}
