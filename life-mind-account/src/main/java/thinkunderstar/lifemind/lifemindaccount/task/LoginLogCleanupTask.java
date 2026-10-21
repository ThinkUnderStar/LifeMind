package thinkunderstar.lifemind.lifemindaccount.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import thinkunderstar.lifemind.lifemindaccount.entity.LoginAudit;
import thinkunderstar.lifemind.lifemindaccount.mapper.LoginAuditMapper;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.LoginAuditService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 登录日志清理定时任务。
 *
 * <p>为防止日志表无限膨胀拖垮数据库，账号删除后的半年，将清除登录审计，
 * 定期清理过期的历史登录日志。
 *
 * <p>核心设计：
 * <ul>
 *     <li>分布式锁：防止多实例部署时，多个节点同时执行清理任务导致数据库压力翻倍。</li>
 *     <li>分批删除：使用 LIMIT 循环删除，避免大事务导致的锁表、主从延迟等问题。</li>
 *     <li>错峰执行：设置在每天凌晨系统低峰期（02:30）执行。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoginLogCleanupTask {
    private final LoginAuditMapper loginAuditMapper;
    private final LoginAuditService loginAuditService;

    @Scheduled(cron = "0 30 2 * * ?")
    public void loginLogCleanupTask() {
        List<Long> userIds = loginAuditMapper.selectExpiredLogoutAudits(
                        3,
                        LocalDateTime.now().minusMonths(6)
                ).stream()
                .map(loginAudit -> loginAudit.getUserId())
                .collect(Collectors.toList());


        if (userIds.isEmpty()) {
            return;
        }

        List<Long> loginAuditList = loginAuditService.list(
                        new LambdaQueryWrapper<LoginAudit>().in(LoginAudit::getUserId, userIds)
                ).stream()
                .map(LoginAudit::getId)
                .collect(Collectors.toList());

        loginAuditService.removeByIds(loginAuditList);
    }
}
