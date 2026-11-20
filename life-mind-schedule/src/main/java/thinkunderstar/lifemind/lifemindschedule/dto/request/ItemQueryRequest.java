package thinkunderstar.lifemind.lifemindschedule.dto.request;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 事项分页查询条件（GET 参数，用 {@code @ModelAttribute} 绑定）。
 *
 * <p>所有条件都可选，不传就是「我的全部事项，按时间倒序」。
 *
 * <p>{@code date} 与 {@code startDate / endDate} 是两种口径，不要同时用：
 * <ul>
 *     <li>{@code date}：只看某一天（日程看开始时间，待办看截止时间），用于「日视图」；</li>
 *     <li>{@code startDate ~ endDate}：闭区间，用于「周视图 / 月视图」。</li>
 * </ul>
 * 同时传时以 {@code date} 为准，实现里只取其一，避免条件互相打架。
 */
@Data
public class ItemQueryRequest {

    /**
     * 页码，从 1 开始，默认 1
     */
    private Integer pageNum = 1;

    /**
     * 每页条数，默认 20；服务层会把它压到 100 以内
     */
    private Integer pageSize = 20;

    /**
     * 事项类型过滤：schedule / todo，不传查全部
     */
    private String itemType;

    /**
     * 状态过滤：0-未完成 1-已完成 2-已取消，不传查全部
     */
    private Integer status;

    /**
     * 标题 / 内容关键词，模糊匹配
     */
    private String keyword;

    /**
     * 只看逾期待办：未完成、有截止时间、且截止时间已经过了。
     *
     * <p>为 true 时忽略 {@code itemType} 与 {@code status}——「逾期」本身
     * 已经限定了「待办 + 未完成」，再叠加别的条件只会让调用方困惑。
     * 概览接口只给一个逾期数字，agent 想知道「具体是哪几条」必须靠这个参数。
     */
    private Boolean overdue;

    /**
     * 只看某一天，格式 yyyy-MM-dd
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /**
     * 区间起点（含），格式 yyyy-MM-dd
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /**
     * 区间终点（含当天），格式 yyyy-MM-dd
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
}
