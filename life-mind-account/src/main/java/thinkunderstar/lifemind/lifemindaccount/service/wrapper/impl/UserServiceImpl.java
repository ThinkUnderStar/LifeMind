package thinkunderstar.lifemind.lifemindaccount.service.wrapper.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import thinkunderstar.lifemind.lifemindaccount.entity.User;
import thinkunderstar.lifemind.lifemindaccount.mapper.UserMapper;
import thinkunderstar.lifemind.lifemindaccount.service.wrapper.UserService;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
}
