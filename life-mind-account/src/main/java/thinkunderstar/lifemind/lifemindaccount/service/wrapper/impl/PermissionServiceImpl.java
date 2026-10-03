package thinkunderstar.lifemind.lifemindaccount.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindaccount.entity.Permission;
import thinkunderstar.lifemind.lifemindaccount.mapper.PermissionMapper;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.PermissionService;

@Service
public class PermissionServiceImpl extends ServiceImpl<PermissionMapper, Permission> implements PermissionService {
}
