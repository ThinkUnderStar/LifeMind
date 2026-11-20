package thinkunderstar.lifemind.lifemindschedule.service.wrapper;

import com.baomidou.mybatisplus.extension.service.IService;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleReminder;

/**
 * 提醒表的通用 CRUD 封装。
 *
 * <p>带并发语义的几条语句（抢占、失败回退、按事项取消）不放在这里：
 * 它们是扫描链路的业务动作，写在 {@code ScheduleReminderMapper} 里，
 * 由 Service 决定什么时候调用。
 */
public interface ScheduleReminderWrapperService extends IService<ScheduleReminder> {
}
