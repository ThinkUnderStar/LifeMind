package thinkunderstar.lifemind.lifemindmessage.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindmessage.entity.Feedback;
import thinkunderstar.lifemind.lifemindmessage.mapper.FeedbackMapper;
import thinkunderstar.lifemind.lifemindmessage.service.wrapper.FeedbackService;

@Service
public class FeedbackServiceImpl extends ServiceImpl<FeedbackMapper, Feedback> implements FeedbackService {
}
