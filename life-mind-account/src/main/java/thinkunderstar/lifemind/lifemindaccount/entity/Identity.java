package thinkunderstar.lifemind.lifemindaccount.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 身份表
 */
@Data
@TableName("identity")
public class Identity {

    /**
     * 身份ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 身份标识，如 ROLE_ADMIN
     */
    private String code;

    /**
     * 身份名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 排序，越小越靠前
     */
    private Integer sort;

    /**
     * 状态：0-禁用 1-正常
     */
    private Integer status;

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
}
