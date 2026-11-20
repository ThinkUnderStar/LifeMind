package thinkunderstar.lifemind.lifemindschedule.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleReminder;
import thinkunderstar.lifemind.lifemindschedule.mapper.ScheduleReminderMapper;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.ScheduleReminderWrapperService;

/**
 * 提醒表 CRUD 实现。
 */
@Service
public class ScheduleReminderWrapperServiceImpl
        extends ServiceImpl<ScheduleReminderMapper, ScheduleReminder>
        implements ScheduleReminderWrapperService {
}
