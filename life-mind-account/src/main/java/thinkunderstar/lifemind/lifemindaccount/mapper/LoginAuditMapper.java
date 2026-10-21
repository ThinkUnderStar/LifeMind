package thinkunderstar.lifemind.lifemindaccount.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import thinkunderstar.lifemind.lifemindaccount.entity.LoginAudit;

import java.time.LocalDateTime;
import java.util.List;

public interface LoginAuditMapper extends BaseMapper<LoginAudit> {

    /**
     * 查询已超过保留期（半年）的注销账号审计记录。
     *
     * <p>审计约定：账号注销后其审计记录仍保留半年再清理。
     * 注销事件在 {@code login_audit} 中以 {@link LoginAudit#TYPE_RETIRE}=3 标记，
     * 本方法返回 type=3（注销）且 create_time 早于 {@code cutoff}（= 当前时间 - 半年）的记录，
     * 供后台定时清理任务调用。
     *
     * @param cutoff 保留截止时间，早于该时间的注销审计视为过期
     * @return 过期的注销账号审计记录
     */
    @Select("SELECT * FROM login_audit WHERE type = #{type} AND create_time < #{cutoff}")
    List<LoginAudit> selectExpiredLogoutAudits(@Param("type") Integer type, @Param("cutoff") LocalDateTime cutoff);
}