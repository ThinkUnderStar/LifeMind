package thinkunderstar.lifemind.lifemindmessage.service.core;

import thinkunderstar.lifemind.lifemindmessage.event.NotificationEvent;

public interface NotificationService {
    void sendNotification(NotificationEvent notificationEvent);
}
