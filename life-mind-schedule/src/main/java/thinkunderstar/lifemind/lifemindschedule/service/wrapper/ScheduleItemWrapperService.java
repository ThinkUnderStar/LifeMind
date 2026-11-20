package thinkunderstar.lifemind.lifemindschedule.service.wrapper;

import com.baomidou.mybatisplus.extension.service.IService;
import thinkunderstar.lifemind.lifemindschedule.entity.ScheduleItem;

/**
 * 事项表的通用 CRUD 封装。
 *
 * <p>与 account / message 服务保持同一分层：Wrapper 只做「表级」操作，
 * 业务规则（归属校验、时间字段约束、提醒联动）一律在 Service 层，
 * 不要把判断塞进 Wrapper，否则换个调用方就会绕过规则。
 */
public interface ScheduleItemWrapperService extends IService<ScheduleItem> {
}
