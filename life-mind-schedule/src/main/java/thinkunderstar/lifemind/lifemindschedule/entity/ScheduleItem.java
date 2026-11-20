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
 * 事项表：日程与待办统一存储，用 {@code itemType} 区分。
 *
 * <p>两者只差几个时间字段，拆两张表会让「今天有什么事」变成两次查询再合并排序，
 * 而列表、状态、提醒、统计的逻辑完全一样。
 *
 * <p>时间字段约束（与建表 SQL 的注释一致）：
 * <ul>
 *     <li>schedule：{@code startTime} 必填，{@code dueTime} 恒为 null</li>
 *     <li>todo：{@code startTime / endTime} 恒为 null，{@code dueTime} 可空</li>
 * </ul>
 */
@Data
@TableName("schedule_item")
public class ScheduleItem {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 归属用户ID：所有查询都必须带这个条件，不能只按 id 查再判断
     */
    private Long userId;

    /**
     * 事项类型：schedule-日程 todo-待办
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
     * 开始时间：日程必填，待办为 null
     */
    private LocalDateTime startTime;

    /**
     * 结束时间，可空
     */
    private LocalDateTime endTime;

    /**
     * 截止时间：待办使用，日程为 null
     */
    private LocalDateTime dueTime;

    /**
     * 是否全天：0-否 1-是
     */
    private Integer allDay;

    /**
     * 优先级：0-低 1-中 2-高
     */
    private Integer priority;

    /**
     * 状态：0-未完成 1-已完成 2-已取消
     */
    private Integer status;

    /**
     * 完成时间；未完成时为 null
     */
    private LocalDateTime completedTime;

    /**
     * 标签，逗号分隔
     */
    private String tags;

    /**
     * 幂等键：同一用户下相同值视为同一次创建。
     *
     * <p>给 agent 用。它会在网络超时后重试，没有这个键「明天九点开会」就会被建两条，
     * 而用户看到的是「服务坏了」。为空表示调用方不需要幂等保护（前端手写场景）。
     */
    private String requestId;

    /**
     * 数据来源：{@code web}-前端手写 {@code agent}-智能体代操作。
     *
     * <p>纯审计字段，只在创建时写一次，后续更新不改它。
     * 用途是回答「这条日程是谁建的」，不参与鉴权——来源可以伪造，
     * 拿它当权限依据等于把安全建立在调用方的自觉上。
     */
    private String source;

    /**
     * 所属重复系列ID，指向 {@code schedule_series.id}；一次性事项为 null。
     *
     * <p>有值说明这条是「某条重复规则展开出来的某一次」。它仍然是一个完整的事项：
     * 有自己的时间、状态、提醒，可以单独改、单独完成、单独删除——
     * 改了不会影响系列，系列之后再重建时才会按规则把它算回来。
     */
    private Long seriesId;

    /**
     * 在系列里是第几次（从 1 开始，含锚点那次）；一次性事项为 null。
     *
     * <p>存下来而不是用 row_number() 现算：实例可以被单独删掉（「这周不练了」），
     * 现算的序号会和用户认知错位——他心里的「第 5 次」不会因为删了第 3 次就变成第 4 次。
     */
    private Integer seriesIndex;

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
     * 逻辑删除：0-未删除 1-已删除。
     *
     * <p>用 {@code @TableLogic} 而不是让每个查询手写 deleted=0：
     * 漏写一处就会把已删除的事项查出来。
     */
    @TableLogic
    private Integer deleted;
}
