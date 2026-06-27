package com.tj.crypto.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tj.crypto.entity.base.PhysicsTimeBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 原始消息实体。
 * 用于数据血缘追溯和消息去重。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("raw_message")
public class RawMessageDO extends PhysicsTimeBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String source;
    private String channel;
    private String symbol;
    private String rawJson;
    private Long receivedTime;
    private String checksum;
    private Boolean processed;
}
