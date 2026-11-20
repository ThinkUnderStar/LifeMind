package thinkunderstar.lifemind.lifemindmessage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import thinkunderstar.lifemind.lifemindmessage.entity.Notification;
import thinkunderstar.lifemind.lifemindmessage.event.NotificationEvent;
import thinkunderstar.lifemind.lifemindmessage.service.core.NotificationService;
import thinkunderstar.lifemind.lifemindmessage.service.wrapper.NotificationWrapperService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知消费幂等回归测试。
 *
 * <p>守护的是「RocketMQ 至少一次投递」这条硬约束：同一条业务事件被投递两次以上时，
 * 通知表只能落一行。没有这层保护时，重复消息会插两条通知，而 need_popup=1 的话
 * 前端会弹两次窗。
 *
 * <p>测试直接调 {@code sendNotification}，不经过 MQ——被验证的是消费逻辑本身，
 * 而 MQ 的重投行为是中间件保证的，不需要在这里模拟。
 */
@SpringBootTest
@DisplayName("通知消费幂等")
class NotificationIdempotentTest {

    /** 测试用户ID：与真实用户id区分，每个用例结束后按它清理 */
    private static final Long TEST_USER_ID = 999_999L;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationWrapperService notificationWrapperService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 物理删除，不能用 {@code remove()}。
     *
     * <p>{@code Notification.deleted} 带 {@code @TableLogic}，{@code remove()} 只把
     * deleted 置 1，行还在表里、event_id 仍占着唯一索引。下一轮测试再插同一个
     * eventId 会撞索引，而 count 查询又因为逻辑删除过滤看不到那行，
     * 表现为「明明没插进去却断言失败」。
     */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", TEST_USER_ID);
    }

    private NotificationEvent buildEvent(String eventId) {
        NotificationEvent event = new NotificationEvent();
        event.setUserId(TEST_USER_ID);
        event.setType("schedule");
        event.setTitle("幂等测试");
        event.setContent("同一条事件被投递两次");
        event.setLevel(0);
        event.setNeedPopup(1);
        event.setEventId(eventId);
        return event;
    }

    private long countByEventId(String eventId) {
        return notificationWrapperService.count(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, TEST_USER_ID)
                .eq(Notification::getEventId, eventId));
    }

    @Test
    @DisplayName("同一个 eventId 投递两次，只落一条")
    void sameEventIdOnlyPersistsOnce() {
        String eventId = "schedule_reminder_9001";

        notificationService.sendNotification(buildEvent(eventId));
        assertThat(countByEventId(eventId)).isEqualTo(1);

        // 第二次：走「先查已存在」的快速路径，直接返回
        notificationService.sendNotification(buildEvent(eventId));
        assertThat(countByEventId(eventId))
                .as("重复投递不应新增通知，否则前端会重复弹窗")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("eventId 随事件体正确落库，不被 BeanUtil 漏拷")
    void eventIdIsPersisted() {
        String eventId = "schedule_reminder_9002";

        notificationService.sendNotification(buildEvent(eventId));

        Notification saved = notificationWrapperService.getOne(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, TEST_USER_ID));
        assertThat(saved).isNotNull();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getNeedPopup()).isEqualTo(1);
    }

    @Test
    @DisplayName("没有 eventId 的老消息不做去重，行为与上线前一致")
    void nullEventIdSkipsDeduplication() {
        notificationService.sendNotification(buildEvent(null));
        notificationService.sendNotification(buildEvent(null));

        // 唯一索引允许多个 NULL，两条都该保留：
        // 这类消息没有幂等保护，不能被误判成重复
        long total = notificationWrapperService.count(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, TEST_USER_ID));
        assertThat(total).isEqualTo(2);
    }
}
