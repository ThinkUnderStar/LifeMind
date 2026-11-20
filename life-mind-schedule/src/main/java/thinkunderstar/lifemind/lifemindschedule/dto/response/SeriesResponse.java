package thinkunderstar.lifemind.lifemindschedule.dto.response;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 重复系列的展示对象。
 *
 * <p>对外把 {@code intervalVal} 暴露成 {@code interval}：API 上「每 2 周」的 2 就该叫 interval，
 * 数据库列名带 Val 后缀只是为了避开关键字，不该泄漏到接口上。
 *
 * <p>{@code itemCount / firstItemId / lastItemId} 是聚合出来的，不是系列表里的列。
 * 没有实例时（全部被删掉）它们为 null——调用方据此判断「这条系列已经空了」，
 * 而不是拿 {@code status} 判断，因为服务端没有后台任务去把空系列标成已结束。
 */
@Data
public class SeriesResponse {

    /**
     * 系列ID
     */
    private Long id;

    /**
     * 展开成什么：schedule-日程 todo-待办
     */
    private String itemType;

    /**
     * 标题
     */
    private String title;

    /**
     * 详细内容
     */
    private String content;

    /**
     * 地点，仅日程有值
     */
    private String location;

    /**
     * 优先级：0-低 1-中 2-高
     */
    private Integer priority;

    /**
     * 是否全天：0-否 1-是
     */
    private Integer allDay;

    /**
     * 标签，逗号分隔
     */
    private String tags;

    /**
     * 频率：DAILY / WEEKLY / MONTHLY
     */
    private String freq;

    /**
     * 间隔：每 N 天/周/月
     */
    private Integer interval;

    /**
     * 星期几，如 ["MO","WE"]；仅 WEEKLY 有值
     */
    private List<String> byDay;

    /**
     * 终止日期（含当天）
     */
    private LocalDate untilDate;

    /**
     * 总次数上限（含第一次）
     */
    private Integer countLimit;

    /**
     * 锚点时刻：日程是开始时间，待办是截止时间
     */
    private LocalDateTime baseTime;

    /**
     * 每次时长（分钟），仅日程
     */
    private Integer durationMinutes;

    /**
     * 尚未发送的提醒偏移（分钟）
     */
    private List<Integer> remindOffsets;

    /**
     * 状态：0-生效 1-已结束。只反映「用户主动终止」，不反映「次数已用完」
     */
    private Integer status;

    /**
     * 数据来源：web / agent
     */
    private String source;

    /**
     * 已展开的实例条数
     */
    private Integer itemCount;

    /**
     * 第一次的实例ID
     */
    private Long firstItemId;

    /**
     * 最后一次的实例ID
     */
    private Long lastItemId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
