package thinkunderstar.lifemind.lifemindaccount.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindaccount.entity.LoginAudit;
import thinkunderstar.lifemind.lifemindaccount.mapper.LoginAuditMapper;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.LoginAuditService;

@Service
public class LoginAuditServiceImpl extends ServiceImpl<LoginAuditMapper, LoginAudit> implements LoginAuditService {
}