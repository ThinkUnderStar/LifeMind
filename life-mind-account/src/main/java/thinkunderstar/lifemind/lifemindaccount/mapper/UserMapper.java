package thinkunderstar.lifemind.lifemindaccount.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import thinkunderstar.lifemind.lifemindaccount.entity.User;

public interface UserMapper extends BaseMapper<User> {

    /**
     * 清除已注销（软删除，deleted=1）账号占用的手机号。
     *
     * <p>MyBatis-Plus 的逻辑删除会自动给查询 / 更新追加 {@code deleted=0} 条件，
     * 而注销账号只是软删除，手机号 / 邮箱字段还在，会一直占着唯一索引，
     * 导致新账号绑定时报「已被其他账号绑定」。这里用原生 SQL 绕过逻辑删除过滤，
     * 把已注销账号的手机号置空，让该号码可被复用（注销账号不依赖手机号 / 邮箱登录）。
     *
     * @param phone 待清除的手机号
     * @return 受影响行数
     */
    @Update("UPDATE user SET phone = NULL, update_time = NOW() WHERE deleted = 1 AND phone = #{phone}")
    int clearPhoneOfDeletedUser(@Param("phone") String phone);

    /**
     * 清除已注销（软删除，deleted=1）账号占用的邮箱，语义与 {@link #clearPhoneOfDeletedUser(String)} 一致。
     *
     * @param email 待清除的邮箱
     * @return 受影响行数
     */
    @Update("UPDATE user SET email = NULL, update_time = NOW() WHERE deleted = 1 AND email = #{email}")
    int clearEmailOfDeletedUser(@Param("email") String email);
}
