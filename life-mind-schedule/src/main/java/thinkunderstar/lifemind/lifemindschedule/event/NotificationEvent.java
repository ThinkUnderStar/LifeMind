package thinkunderstar.lifemind.lifemindschedule.event;

import lombok.Data;

/**
 * 服务间通知消息体。
 *
 * <p>字段必须与 message 服务消费端的 {@code NotificationEvent} 逐字对齐：
 * 两边靠 JSON 反序列化对接，少一个字段只是拿到 null，多一个字段会被忽略，
 * 都不会报错——这种「静默不匹配」只能靠人工保证，所以这里不做裁剪，
 * 把消费端定义的字段原样复制一份（包括本服务暂时不用的 senderId / jumpUrl）。
 *
 * <p>为什么不依赖 message 模块的类：微服务之间共享实体类会让两个服务必须
 * 同版本发布，一条字段改动能卡住两边的发版。消息契约用「各存一份、字段对齐」的方式，
 * 代价是改名要手动同步，收益是服务之间不编译期耦合。
 */
@Data
public class NotificationEvent {

    /**
     * 接收人用户ID
     */
    private Long userId;

    /**
     * 通知类型：本服务固定用 schedule（日程/待办提醒）
     */
    private String type;

    /**
     * 通知标题
     */
    private String title;

    /**
     * 通知内容
     */
    private String content;

    /**
     * 发送者用户ID，系统通知为 null
     */
    private Long senderId;

    /**
     * 关联业务类型：本服务用 schedule_item
     */
    private String bizType;

    /**
     * 关联业务ID：事项ID 的字符串形式
     */
    private String bizId;

    /**
     * 点击通知后的跳转地址，可空
     */
    private String jumpUrl;

    /**
     * 通知级别：0-普通 1-重要 2-紧急
     */
    private Integer level;

    /**
     * 是否需要弹窗提醒：0-否 1-是
     *
     * <p>本服务的提醒恒为 1：用户设了「到点提醒」，到点不出声就等于没提醒。
     *
     * <p>注意：新增字段必须同步到 message 服务的同名类，否则两边 JSON
     * 反序列化时会静默拿到 null，不报错。
     */
    private Integer needPopup;

    /**
     * 事件幂等键：本服务填 {@code schedule_reminder_{reminderId}}。
     *
     * <p>粒度必须是「哪一次提醒」而不是「哪个事项」：一个事项可以有多个提醒时刻
     * （提前一天、提前 30 分钟），它们的 bizId 都是同一个 item.id，用它去重会把
     * 正常的第二、三条提醒误挡掉。reminder.id 一行一个提醒时刻，天然唯一。
     *
     * <p>带 {@code schedule_reminder_} 前缀是因为 message 服务是通用的通知落库方，
     * 以后别的服务也会发事件，各自主键空间独立，裸数字会互相撞车。
     *
     * <p>这是投递重试的安全网：扫描任务投递失败会把 status 回退到待发送，
     * 下一轮重新抢占后再发一次；若第一次其实已经进了队列，两条消息的 eventId
     * 相同，消费端只会落一条通知、只弹一次窗。
     *
     * <p>注意：新增字段必须同步到 message 服务的同名类，否则两边 JSON
     * 反序列化时会静默拿到 null，不报错。
     */
    private String eventId;

    /**
     * 扩展数据，JSON 字符串
     */
    private String extra;
}
