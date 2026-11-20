package thinkunderstar.lifemind.lifemindschedule.dto.request;

import lombok.Data;

/**
 * 备忘录分页查询条件。
 *
 * <p>标签用「包含」匹配而不是精确相等：标签存在一个逗号分隔的字符串里，
 * 精确匹配会漏掉「工作,重要」这种组合标签下的「工作」。
 */
@Data
public class MemoQueryRequest {

    /**
     * 页码，从 1 开始，默认 1
     */
    private Integer pageNum = 1;

    /**
     * 每页条数，默认 20；服务层压到 100 以内
     */
    private Integer pageSize = 20;

    /**
     * 标题 / 正文关键词，模糊匹配
     */
    private String keyword;

    /**
     * 标签过滤
     */
    private String tags;
}
