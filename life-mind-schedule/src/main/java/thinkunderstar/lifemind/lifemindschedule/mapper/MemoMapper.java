package thinkunderstar.lifemind.lifemindschedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import thinkunderstar.lifemind.lifemindschedule.entity.Memo;

/**
 * 备忘录 Mapper。
 *
 * <p>全部是单表条件查询与分页，{@link BaseMapper} 已够用，不需要手写 SQL。
 */
public interface MemoMapper extends BaseMapper<Memo> {
}
