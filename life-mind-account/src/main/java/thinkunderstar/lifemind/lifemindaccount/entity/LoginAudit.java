package thinkunderstar.lifemind.lifemindaccount.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录审计表。
 *
 * <p>记录每次登录的来源信息，以及账号创建、注销的发生时间，通过 {@link #type} 区分。
 * 与 user 表不建立外键 / 关联索引，即使账号注销，审计记录也仍保留（约定保留半年后清理）。
 * 该表不做逻辑删除，清理走物理删除策略，避免误删审计数据。
 */
@Data
@TableName("login_audit")
public class LoginAudit {

    /**
     * 登录类型：登录
     */
    public static final int TYPE_LOGIN = 1;

    /**
     * 登录类型：创建账号
     */
    public static final int TYPE_REGISTER = 2;

    /**
     * 登录类型：注销账号
     */
    public static final int TYPE_RETIRE = 3;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID（不建外键，账号注销后记录仍保留半年）
     */
    private Long userId;

    /**
     * 审计类型：1-登录 2-创建账号 3-注销账号
     */
    private Integer type;

    /**
     * 来源IP
     */
    private String ip;

    /**
     * 来源地区（如：中国-广东-深圳）
     */
    private String region;

    /**
     * User-Agent，客户端指纹/设备信息
     */
    private String userAgent;

    /**
     * 设备类型：1-PC 2-移动端 3-其他
     */
    private Integer deviceType;

    /**
     * 事件创建时间（登录/创建/注销的发生时间）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}