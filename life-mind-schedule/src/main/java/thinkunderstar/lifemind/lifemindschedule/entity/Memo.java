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
 * 备忘录表。
 *
 * <p>与事项分表的原因：备忘录没有时间点和完成状态，塞进事项表只会多出恒为 null 的列。
 *
 * <p>标题可空：随手记一句话时不该被迫起标题，前端用正文首行兜底。
 */
@Data
@TableName("memo")
public class Memo {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 归属用户ID
     */
    private Long userId;

    /**
     * 标题，可为空
     */
    private String title;

    /**
     * 正文
     */
    private String content;

    /**
     * 标签，逗号分隔
     */
    private String tags;

    /**
     * 是否置顶：0-否 1-是
     */
    private Integer pinned;

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
