package thinkunderstar.lifemind.lifemindaccount.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindaccount.entity.UserIdentity;
import thinkunderstar.lifemind.lifemindaccount.mapper.UserIdentityMapper;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserIdentityService;

@Service
public class UserIdentityServiceImpl extends ServiceImpl<UserIdentityMapper, UserIdentity> implements UserIdentityService {
}
