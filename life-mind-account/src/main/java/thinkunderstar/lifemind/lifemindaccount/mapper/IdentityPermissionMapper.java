package thinkunderstar.lifemind.lifemindaccount.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import thinkunderstar.lifemind.lifemindaccount.entity.IdentityPermission;
import thinkunderstar.lifemind.lifemindaccount.entity.Permission;

import java.util.Collection;
import java.util.List;

public interface IdentityPermissionMapper extends BaseMapper<IdentityPermission> {

    /**
     * 根据身份ID集合查询这些身份拥有的所有权限（已去重）。
     * 多个身份可能拥有同一权限，因此使用 DISTINCT 去重。
     *
     * @param identityIds 身份ID集合
     * @return 去重后的权限列表，identityIds 为空时返回空列表
     */
    @Select("""
            <script>
            SELECT DISTINCT p.*
            FROM identity_permission ip
            INNER JOIN permission p ON p.id = ip.permission_id
            <where>
                <if test="identityIds != null and identityIds.size() > 0">
                    ip.identity_id IN
                    <foreach collection="identityIds" item="identityId" open="(" separator="," close=")">
                        #{identityId}
                    </foreach>
                </if>
                <if test="identityIds == null or identityIds.size() == 0">
                    1 = 0
                </if>
            </where>
            ORDER BY p.sort ASC, p.id ASC
            </script>
            """)
    List<Permission> selectPermissionsByIdentityIds(@Param("identityIds") Collection<Long> identityIds);
}
