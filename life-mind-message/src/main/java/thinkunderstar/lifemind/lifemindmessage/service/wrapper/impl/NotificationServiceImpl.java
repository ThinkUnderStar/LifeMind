package thinkunderstar.lifemind.lifemindmessage.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindmessage.entity.Notification;
import thinkunderstar.lifemind.lifemindmessage.mapper.NotificationMapper;
import thinkunderstar.lifemind.lifemindmessage.service.wrapper.NotificationService;

@Service
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification> implements NotificationService {
}
