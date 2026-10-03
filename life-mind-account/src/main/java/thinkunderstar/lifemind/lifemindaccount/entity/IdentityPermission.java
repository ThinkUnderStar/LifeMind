package thinkunderstar.lifemind.lifemindaccount.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 身份-权限关系表
 */
@Data
@TableName("identity_permission")
public class IdentityPermission {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 身份ID
     */
    private Long identityId;

    /**
     * 权限ID
     */
    private Long permissionId;

    /**
     * 授权时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
