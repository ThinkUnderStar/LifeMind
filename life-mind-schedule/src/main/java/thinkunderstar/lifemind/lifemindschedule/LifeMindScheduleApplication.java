package thinkunderstar.lifemind.lifemindschedule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 日程插件服务入口。
 *
 * <p>职责：日程、待办、备忘录的增删改查，以及到点提醒的扫描与投递。
 * 提醒不直接调通知服务，而是投递到 MQ，由 message 服务的消费者落库——
 * 这样本服务不需要知道通知渠道（站内信 / 短信 / 邮件）以后怎么变。
 *
 * <p>{@code @EnableScheduling} 是提醒扫描任务的前提，去掉后提醒不会触发，
 * 但接口全部正常，属于「看起来没坏、其实功能少了一半」的故障。
 */
@SpringBootApplication
@EnableScheduling
public class LifeMindScheduleApplication {

    public static void main(String[] args) {
        SpringApplication.run(LifeMindScheduleApplication.class, args);
    }

}
