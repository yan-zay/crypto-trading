-- 配置版本管理表
CREATE TABLE IF NOT EXISTS admin_config_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_type VARCHAR(20) NOT NULL COMMENT '配置类型(STRATEGY/FACTOR/RISK/CONNECTOR/EXECUTION)',
    config_key VARCHAR(100) NOT NULL COMMENT '配置键',
    version_id VARCHAR(50) NOT NULL COMMENT '版本唯一标识',
    status VARCHAR(20) NOT NULL COMMENT '状态(DRAFT/VALIDATED/PUBLISHED/ACTIVE/ROLLED_BACK/ARCHIVED)',
    content_json TEXT COMMENT '配置内容JSON',
    checksum VARCHAR(64) COMMENT '内容校验和',
    created_by VARCHAR(50) COMMENT '创建人',
    published_by VARCHAR(50) COMMENT '发布人',
    published_time TIMESTAMP NULL COMMENT '发布时间',
    rollback_from_version VARCHAR(50) COMMENT '回滚源版本ID',
    remark VARCHAR(500) COMMENT '备注',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_version_id (version_id),
    INDEX idx_type_key_status (config_type, config_key, status),
    INDEX idx_type_key (config_type, config_key)
) ENGINE=InnoDB COMMENT='配置版本管理';

-- 审计日志表
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_type VARCHAR(30) NOT NULL COMMENT '操作类型(CREATE/VALIDATE/PUBLISH/ROLLBACK)',
    config_type VARCHAR(20) COMMENT '配置类型',
    config_key VARCHAR(100) COMMENT '配置键',
    version_id VARCHAR(50) COMMENT '版本ID',
    operator VARCHAR(50) NOT NULL COMMENT '操作人',
    operation_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    detail TEXT COMMENT '操作详情JSON',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_operation_time (operation_type, operation_time),
    INDEX idx_config (config_type, config_key)
) ENGINE=InnoDB COMMENT='审计日志';
