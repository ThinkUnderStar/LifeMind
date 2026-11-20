package thinkunderstar.lifemind.lifemindschedule.dto.request;

import lombok.Data;

/**
 * 重复系列列表的过滤条件（GET 参数）。
 *
 * <p>刻意不分页：一个用户的重复计划是「习惯」级别的数量，十几条就算多了。
 * 真到需要分页那天再加，现在加分页只会让 agent 多一层翻页逻辑。
 */
@Data
public class SeriesQueryRequest {

    /**
     * 按展开类型过滤：schedule / todo，不传查全部
     */
    private String itemType;

    /**
     * 按状态过滤：0-生效 1-已结束，不传查全部
     */
    private Integer status;
}
