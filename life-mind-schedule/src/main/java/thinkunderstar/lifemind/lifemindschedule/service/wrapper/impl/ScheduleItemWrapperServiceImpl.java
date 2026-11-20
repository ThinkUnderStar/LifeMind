package thinkunderstar.lifemind.lifemindschedule.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleItem;
import thinkunderstar.lifemind.lifemindschedule.mapper.ScheduleItemMapper;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.ScheduleItemWrapperService;

/**
 * 事项表 CRUD 实现：继承 MyBatis-Plus 的 {@code ServiceImpl}，不写任何自定义方法。
 */
@Service
public class ScheduleItemWrapperServiceImpl
        extends ServiceImpl<ScheduleItemMapper, ScheduleItem>
        implements ScheduleItemWrapperService {
}
