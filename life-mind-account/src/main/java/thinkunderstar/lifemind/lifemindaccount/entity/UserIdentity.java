package thinkunderstar.lifemind.lifemindaccount.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-身份关系表
 */
@Data
@TableName("user_identity")
public class UserIdentity {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 身份ID
     */
    private Long identityId;

    /**
     * 绑定时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
