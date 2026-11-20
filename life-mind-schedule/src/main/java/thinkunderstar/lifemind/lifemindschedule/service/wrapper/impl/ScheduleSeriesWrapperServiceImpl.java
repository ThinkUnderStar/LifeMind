package thinkunderstar.lifemind.lifemindschedule.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleSeries;
import thinkunderstar.lifemind.lifemindschedule.mapper.ScheduleSeriesMapper;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.ScheduleSeriesWrapperService;

/**
 * 重复系列表 CRUD 实现：不写任何自定义方法。
 */
@Service
public class ScheduleSeriesWrapperServiceImpl
        extends ServiceImpl<ScheduleSeriesMapper, ScheduleSeries>
        implements ScheduleSeriesWrapperService {
}
