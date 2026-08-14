-- 管理后台：用户表
CREATE TABLE IF NOT EXISTS admin_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    role VARCHAR(20) NOT NULL DEFAULT 'VIEWER' COMMENT '角色(VIEWER/RESEARCHER/OPERATOR/RISK_MANAGER/ADMIN)',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    INDEX idx_role (role)
) ENGINE=InnoDB COMMENT='管理后台用户';

-- 插入默认管理员账户（密码: admin123，BCrypt 哈希）
INSERT IGNORE INTO admin_user (username, password_hash, role, enabled) VALUES
('admin', '$2a$10$G6TEcRNH0v7CUBBVehWvReTLMwx/9PhpNYS6/B3cP4Ffo.Dx5bFEK', 'ADMIN', 1);

-- 插入默认只读用户（密码: viewer123）
INSERT IGNORE INTO admin_user (username, password_hash, role, enabled) VALUES
('viewer', '$2a$10$mfVXe5wdfp9bnpMy7nN8uudRir27.YlHPQAmNybfmyfCDwn0JElkC', 'VIEWER', 1);
