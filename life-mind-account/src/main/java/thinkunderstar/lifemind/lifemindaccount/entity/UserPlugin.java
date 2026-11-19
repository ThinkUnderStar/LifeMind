package thinkunderstar.lifemind.lifemindaccount.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户插件启用表：一行一用户一插件。
 *
 * <p>保留 {@code enabled} 列而不是「有行=启用」：默认启用的插件被用户关掉后没有行可删，
 * 无法表达「我关了它」；而「从未配置」应当跟随默认值变化，「配置成关闭」不该被默认值覆盖。
 */
@Data
@TableName("user_plugin")
public class UserPlugin {

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
     * 插件ID
     */
    private Long pluginId;

    /**
     * 0-已停用 1-已启用
     */
    private Integer enabled;

    /**
     * 首次配置时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 最后变更时间，停用时即为停用时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
