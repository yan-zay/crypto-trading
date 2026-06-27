package com.tj.crypto.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tj.crypto.entity.base.PhysicsTimeBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 配置版本持久化实体。
 * 对应 admin_config_version 表，存储配置版本全生命周期数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("admin_config_version")
public class ConfigVersionDO extends PhysicsTimeBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置类型 */
    private String configType;

    /** 配置键 */
    private String configKey;

    /** 版本唯一标识 */
    private String versionId;

    /** 状态 */
    private String status;

    /** 配置内容 JSON */
    private String contentJson;

    /** 内容校验和 */
    private String checksum;

    /** 创建人 */
    private String createdBy;

    /** 发布人 */
    private String publishedBy;

    /** 发布时间 */
    private Date publishedTime;

    /** 回滚源版本 ID */
    private String rollbackFromVersion;

    /** 备注 */
    private String remark;
}
