package thinkunderstar.lifemind.lifemindschedule.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量操作的结果：逐条回报，不是简单的「成功 / 失败」。
 *
 * <p>为什么不用「全部成功才返回 200」：批量删 5 条，其中 1 条已经被删过了，
 * 整体回滚意味着用户重试时前 4 条要再删一遍——而它们已经不存在了。
 * 逐条回报能让调用方只对失败项做补救，也让 agent 能如实告诉用户
 * 「3 条已删除，1 条找不到」。
 */
@Data
public class BatchResultResponse {

    /**
     * 成功条数
     */
    private Integer successCount;

    /**
     * 成功的事项ID
     */
    private List<Long> succeeded = new ArrayList<>();

    /**
     * 失败的条目及原因
     */
    private List<BatchFailure> failed = new ArrayList<>();

    /**
     * 单条失败明细
     */
    @Data
    public static class BatchFailure {

        /**
         * 事项ID
         */
        private Long id;

        /**
         * 失败原因（直接取业务异常的中文提示）
         */
        private String reason;

        public BatchFailure(Long id, String reason) {
            this.id = id;
            this.reason = reason;
        }
    }

    /**
     * 记一条成功
     */
    public void addSuccess(Long id) {
        succeeded.add(id);
        successCount = succeeded.size();
    }

    /**
     * 记一条失败
     */
    public void addFailure(Long id, String reason) {
        failed.add(new BatchFailure(id, reason));
    }
}
