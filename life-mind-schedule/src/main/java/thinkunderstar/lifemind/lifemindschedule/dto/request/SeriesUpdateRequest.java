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
import java.util.List;

/**
 * 修改重复系列。
 *
 * <p>两种范围，语义差别很大，必须显式选一个：
 * <ul>
 *     <li>{@code all}：整个系列（包括锚点那次）按新设置走；</li>
 *     <li>{@code future}：只从 {@code anchorItemId} 那一次起往后改，之前的不动。</li>
 * </ul>
 * 没有 {@code single}：「只改这一次」直接调 {@code PUT /item/update}，
 * 那条实例本来就是独立的一行，不需要经过系列。混进来只会让一个接口有两种返回类型。
 *
 * <p><b>规则字段</b>（{@code freq / interval / byDay / untilDate / countLimit}）
 * 只要传了任意一个，就视为「规则变了」，服务端会删掉对应范围内的未来实例再按新规则重建。
 * 之所以不能只改规则不重建：实例是物化的实体行，改了规则不同步实例，
 * 日历上显示的就还是旧的日子。
 *
 * <p><b>内容字段</b>（{@code title / content / location / priority / allDay / tags}）
 * 改动会批量写到未来未完成的实例上；已经发生或已完成的实例保持原样。
 *
 * <p>不支持 {@code clearFields}：清空备注、去掉地点这类操作通常是「这一条」的想法，
 * 而不是「以后每次都不要地点」。真要清空，改单条实例。
 */
@Data
public class SeriesUpdateRequest {

    /**
     * 系列ID
     */
    @NotNull(message = "系列ID不能为空")
    private Long id;

    /**
     * 修改范围：all-整个系列 future-从锚点那次起往后
     */
    @NotBlank(message = "修改范围不能为空")
    @Pattern(regexp = "all|future", message = "修改范围只能是 all 或 future")
    private String scope;

    /**
     * 锚点实例ID：{@code scope=future} 时必填，表示「从这一次起」。
     *
     * <p>用实例 id 而不是日期：用户说「从下周三那次开始改」，agent 手里拿到的就是
     * 列表里那一条的 id；让它自己换算日期只会多一次出错的机会。
     */
    private Long anchorItemId;

    /**
     * 新标题
     */
    @Size(max = 200, message = "标题不能超过 200 个字")
    private String title;

    /**
     * 新备注内容
     */
    private String content;

    /**
     * 新地点
     */
    @Size(max = 255, message = "地点不能超过 255 个字")
    private String location;

    /**
     * 新优先级 0-低 1-中 2-高
     */
    @Min(value = 0, message = "优先级只能是 0、1、2")
    @Max(value = 2, message = "优先级只能是 0、1、2")
    private Integer priority;

    /**
     * 是否全天：0-否 1-是
     */
    @Min(value = 0, message = "全天标记只能是 0 或 1")
    @Max(value = 1, message = "全天标记只能是 0 或 1")
    private Integer allDay;

    /**
     * 新标签，逗号分隔
     */
    @Size(max = 255, message = "标签总长度不能超过 255 个字")
    private String tags;

    /**
     * 新频率：DAILY / WEEKLY / MONTHLY。传了就触发实例重建
     */
    @Pattern(regexp = "DAILY|WEEKLY|MONTHLY", message = "频率只能是 DAILY、WEEKLY 或 MONTHLY")
    private String freq;

    /**
     * 新间隔：每 N 天/周/月
     */
    @Min(value = 1, message = "间隔至少是 1")
    @Max(value = 30, message = "间隔最多 30")
    private Integer interval;

    /**
     * 新星期几；仅 {@code freq=WEEKLY}（或系列本来就是 WEEKLY）可用
     */
    @Size(max = 7, message = "一个星期最多 7 天")
    private List<@Pattern(regexp = "MO|TU|WE|TH|FR|SA|SU",
            message = "星期几只能取 MO、TU、WE、TH、FR、SA、SU") String> byDay;

    /**
     * 新终止日期（含当天）
     */
    private LocalDate untilDate;

    /**
     * 新总次数上限
     */
    @Min(value = 1, message = "次数至少是 1")
    @Max(value = 100, message = "次数最多 100，更长的重复请分段创建")
    private Integer countLimit;

    /**
     * 新的提醒偏移；空列表表示取消全部提醒；不传表示不改。
     *
     * <p>非空时会逐个重建未来实例的提醒（每个实例按自己的时刻算，不是一个提醒管全部）。
     */
    @Valid
    @Size(max = 10, message = "一个系列最多设置 10 个提醒")
    private List<@Min(value = 0, message = "提醒偏移不能为负数")
                 @Max(value = 10080, message = "提醒最多提前 7 天") Integer> remindOffsets;
}
