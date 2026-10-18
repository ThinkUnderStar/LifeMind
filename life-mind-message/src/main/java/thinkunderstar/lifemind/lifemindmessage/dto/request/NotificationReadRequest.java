package thinkunderstar.lifemind.lifemindmessage.dto.request;

import lombok.Data;

/**
 * 标记通知已读请求参数。
 */
@Data
public class NotificationReadRequest {

    /** 通知 ID */
    private Long id;
}
