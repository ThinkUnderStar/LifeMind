package thinkunderstar.lifemind.lifemindschedule.service.wrapper;

import com.baomidou.mybatisplus.extension.service.IService;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleSeries;

/**
 * 重复系列表的通用 CRUD 封装。
 *
 * <p>与事项表同一分层口径：Wrapper 只做表级操作，展开规则、归属校验、
 * 实例的删与建一律在 Service 层。
 */
public interface ScheduleSeriesWrapperService extends IService<ScheduleSeries> {
}
