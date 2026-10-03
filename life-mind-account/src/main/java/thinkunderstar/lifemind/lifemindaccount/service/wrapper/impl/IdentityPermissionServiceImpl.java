package thinkunderstar.lifemind.lifemindaccount.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindaccount.entity.IdentityPermission;
import thinkunderstar.lifemind.lifemindaccount.mapper.IdentityPermissionMapper;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.IdentityPermissionService;

@Service
public class IdentityPermissionServiceImpl extends ServiceImpl<IdentityPermissionMapper, IdentityPermission> implements IdentityPermissionService {
}
