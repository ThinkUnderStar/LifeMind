package thinkunderstar.lifemind.lifemindaccount.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindaccount.entity.Identity;
import thinkunderstar.lifemind.lifemindaccount.mapper.IdentityMapper;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.IdentityService;

@Service
public class IdentityServiceImpl extends ServiceImpl<IdentityMapper, Identity> implements IdentityService {
}
