package com.tj.crypto.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 管理后台用户实体。
 * 对应 admin_user 表，存储登录凭据和角色信息。
 */
@Data
@TableName("admin_user")
public class UserDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名，唯一 */
    private String username;

    /** BCrypt 密码哈希 */
    private String passwordHash;

    /** 角色 */
    private String role;

    /** 是否启用 */
    private Boolean enabled;

    /** 创建时间 */
    private Date createdAt;
}
