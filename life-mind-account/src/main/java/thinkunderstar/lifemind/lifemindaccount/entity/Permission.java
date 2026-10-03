package thinkunderstar.lifemind.lifemindaccount.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限表
 */
@Data
@TableName("permission")
public class Permission {

    /**
     * 权限ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 权限标识，如 account:user:list
     */
    private String code;

    /**
     * 权限名称
     */
    private String name;

    /**
     * 类型：1-菜单 2-按钮 3-接口
     */
    private Integer type;

    /**
     * 父权限ID，0 表示顶级
     */
    private Long parentId;

    /**
     * 前端路由 / 资源路径
     */
    private String path;

    /**
     * HTTP 方法，type=3 时使用
     */
    private String method;

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
