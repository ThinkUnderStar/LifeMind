package thinkunderstar.lifemind.lifemindschedule.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindschedule.entity.Memo;
import thinkunderstar.lifemind.lifemindschedule.mapper.MemoMapper;
import thinkunderstar.lifemind.lifemindschedule.service.wrapper.MemoWrapperService;

/**
 * 备忘录 CRUD 实现。
 */
@Service
public class MemoWrapperServiceImpl
        extends ServiceImpl<MemoMapper, Memo>
        implements MemoWrapperService {
}
