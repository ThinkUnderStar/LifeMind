package thinkunderstar.lifemind.lifemindaccount.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 插件目录表：有哪些可插拔插件、是否上架、新用户默认是否启用。
 *
 * <p>用户自己的开关在 {@link UserPlugin}，不在这张表上：
 * 上下架是全局动作，启停是个人动作，混在一起会出现「一个人下架影响所有人」。
 */
@Data
@TableName("plugin")
public class Plugin {

    /**
     * 插件ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 插件标识：todo / schedule / bill / health，与 agent 工具名前缀一致。
     *
     * <p>只增不改：改名等于换插件，前端已存的启用记录和历史服务卡都会对不上。
     */
    private String pluginKey;

    /**
     * 展示名，如「待办」
     */
    private String name;

    /**
     * 一句话说明，插件市场用
     */
    private String description;

    /**
     * 图标标识，前端按它取图标
     */
    private String icon;

    /**
     * 版本号
     */
    private String version;

    /**
     * 上架状态：0-下架 1-上架。
     *
     * <p>下架等于「拔」：用户配置保留，但不参与工具绑定。
     */
    private Integer status;

    /**
     * 新用户是否默认启用：0-否 1-是。
     *
     * <p>用户没有 user_plugin 记录时以此为准；用户显式配置过就以用户配置为准。
     */
    private Integer defaultEnabled;

    /**
     * 排序，越小越靠前
     */
    private Integer sort;

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
