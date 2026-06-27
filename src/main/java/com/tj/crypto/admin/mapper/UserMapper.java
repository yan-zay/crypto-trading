package com.tj.crypto.admin.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * 管理后台用户 Mapper。
 * 封装 admin_user 表的查询逻辑。
 */
@Mapper
public interface UserMapper extends BaseMapperX<UserDO> {

    /**
     * 按用户名查找用户。
     */
    default Optional<UserDO> selectByUsername(String username) {
        return Optional.ofNullable(
                selectOne(new LambdaQueryWrapper<UserDO>()
                        .eq(UserDO::getUsername, username)));
    }
}
