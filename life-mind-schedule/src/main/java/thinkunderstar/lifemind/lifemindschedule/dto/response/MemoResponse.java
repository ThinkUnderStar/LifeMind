package thinkunderstar.lifemind.lifemindschedule.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 备忘录的展示对象。
 */
@Data
public class MemoResponse {

    /**
     * 备忘录ID
     */
    private Long id;

    /**
     * 标题，可能为空
     */
    private String title;

    /**
     * 正文
     */
    private String content;

    /**
     * 标签，逗号分隔
     */
    private String tags;

    /**
     * 是否置顶：0-否 1-是
     */
    private Integer pinned;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
