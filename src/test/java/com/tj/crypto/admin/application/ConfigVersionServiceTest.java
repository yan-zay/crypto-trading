package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.AuditLogDO;
import com.tj.crypto.admin.domain.ConfigStatus;
import com.tj.crypto.admin.domain.ConfigType;
import com.tj.crypto.admin.domain.ConfigVersion;
import com.tj.crypto.admin.domain.ConfigVersionDO;
import com.tj.crypto.admin.mapper.AuditLogMapper;
import com.tj.crypto.admin.mapper.ConfigVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigVersionServiceTest {

    private ConfigVersionMapper configVersionMapper;
    private AuditLogMapper auditLogMapper;
    private ApplicationEventPublisher eventPublisher;
    private ConfigVersionService service;

    /** 模拟数据库：versionId -> ConfigVersionDO */
    private final Map<String, ConfigVersionDO> db = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        configVersionMapper = mock(ConfigVersionMapper.class);
        auditLogMapper = mock(AuditLogMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ConfigVersionService(configVersionMapper, auditLogMapper, eventPublisher);
        db.clear();
        idSeq.set(1);

        // insert: 分配 ID，存入 db
        when(configVersionMapper.insert(any(ConfigVersionDO.class))).thenAnswer(invocation -> {
            ConfigVersionDO entity = invocation.getArgument(0);
            entity.setId(idSeq.getAndIncrement());
            db.put(entity.getVersionId(), entity);
            return 1;
        });

        // updateById: no-op（实体已通过引用被修改）
        when(configVersionMapper.updateById(any(ConfigVersionDO.class))).thenReturn(1);

        // selectByVersionId: 从 db 返回
        when(configVersionMapper.selectByVersionId(any())).thenAnswer(invocation -> {
            String versionId = invocation.getArgument(0);
            return Optional.ofNullable(db.get(versionId));
        });

        // selectActiveByTypeAndKey: 查找 ACTIVE 状态
        when(configVersionMapper.selectActiveByTypeAndKey(any(), any())).thenAnswer(invocation -> {
            String type = invocation.getArgument(0);
            String key = invocation.getArgument(1);
            return db.values().stream()
                    .filter(e -> type.equals(e.getConfigType())
                            && key.equals(e.getConfigKey())
                            && "active".equals(e.getStatus()))
                    .findFirst();
        });

        // selectHistoryByTypeAndKey
        when(configVersionMapper.selectHistoryByTypeAndKey(any(), any())).thenAnswer(invocation -> {
            String type = invocation.getArgument(0);
            String key = invocation.getArgument(1);
            return db.values().stream()
                    .filter(e -> type.equals(e.getConfigType()) && key.equals(e.getConfigKey()))
                    .sorted((a, b) -> a.getCreateTime().compareTo(b.getCreateTime()))
                    .toList();
        });

        // selectActiveByType
        when(configVersionMapper.selectActiveByType(any())).thenAnswer(invocation -> {
            String type = invocation.getArgument(0);
            return db.values().stream()
                    .filter(e -> type.equals(e.getConfigType()) && "active".equals(e.getStatus()))
                    .toList();
        });

        // auditLogMapper.insert: no-op
        when(auditLogMapper.insert(any(AuditLogDO.class))).thenReturn(1);

        // eventPublisher: no-op
        doNothing().when(eventPublisher).publishEvent(any());
    }

    @Nested
    @DisplayName("createDraft")
    class CreateDraft {

        @Test
        @DisplayName("创建草稿版本，状态为 DRAFT")
        void shouldCreateDraftWithDraftStatus() {
            ConfigVersion draft = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross",
                    "{\"fastPeriod\":12,\"slowPeriod\":26}", "初始配置");

            assertThat(draft.versionId()).startsWith("cv-");
            assertThat(draft.type()).isEqualTo(ConfigType.STRATEGY);
            assertThat(draft.configKey()).isEqualTo("MacdCross");
            assertThat(draft.contentJson()).isEqualTo("{\"fastPeriod\":12,\"slowPeriod\":26}");
            assertThat(draft.status()).isEqualTo(ConfigStatus.DRAFT);
            assertThat(draft.remark()).isEqualTo("初始配置");
            assertThat(draft.publishedBy()).isNull();
            assertThat(draft.createdAt()).isEqualTo(draft.updatedAt());
        }

        @Test
        @DisplayName("同一配置键可创建多个草稿")
        void shouldAllowMultipleDraftsForSameKey() {
            ConfigVersion draft1 = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{\"v\":1}", "v1");
            ConfigVersion draft2 = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{\"v\":2}", "v2");

            assertThat(draft1.versionId()).isNotEqualTo(draft2.versionId());
            List<ConfigVersion> history = service.getHistory(ConfigType.STRATEGY, "MacdCross");
            assertThat(history).hasSize(2);
        }

        @Test
        @DisplayName("创建草稿时记录审计日志")
        void shouldRecordAuditLogOnCreate() {
            service.createDraft(ConfigType.STRATEGY, "MacdCross", "{}", "test");

            ArgumentCaptor<AuditLogDO> captor = ArgumentCaptor.forClass(AuditLogDO.class);
            verify(auditLogMapper).insert(captor.capture());
            AuditLogDO auditLog = captor.getValue();
            assertThat(auditLog.getOperationType()).isEqualTo("CREATE");
            assertThat(auditLog.getConfigType()).isEqualTo("strategy");
            assertThat(auditLog.getConfigKey()).isEqualTo("MacdCross");
        }
    }

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("验证 DRAFT 版本，状态变为 VALIDATED")
        void shouldValidateDraft() {
            ConfigVersion draft = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{}", "");

            ConfigVersion validated = service.validate(draft.versionId());

            assertThat(validated.status()).isEqualTo(ConfigStatus.VALIDATED);
            assertThat(validated.versionId()).isEqualTo(draft.versionId());
        }

        @Test
        @DisplayName("非 DRAFT 状态不能验证")
        void shouldRejectValidateNonDraft() {
            ConfigVersion draft = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{}", "");
            ConfigVersion validated = service.validate(draft.versionId());

            assertThatThrownBy(() -> service.validate(validated.versionId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("validate")
                    .hasMessageContaining("validated");
        }

        @Test
        @DisplayName("不存在的版本 ID 抛出异常")
        void shouldThrowForNonExistentVersion() {
            assertThatThrownBy(() -> service.validate("non-existent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    @DisplayName("publish")
    class Publish {

        @Test
        @DisplayName("发布 VALIDATED 版本，变为 ACTIVE")
        void shouldPublishValidatedVersion() {
            ConfigVersion draft = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{}", "");
            ConfigVersion validated = service.validate(draft.versionId());

            ConfigVersion active = service.publish(validated.versionId(), "admin");

            assertThat(active.status()).isEqualTo(ConfigStatus.ACTIVE);
            assertThat(active.publishedBy()).isEqualTo("admin");
        }

        @Test
        @DisplayName("发布新版本时旧 ACTIVE 版本变为 ARCHIVED")
        void shouldArchiveOldActiveVersion() {
            // 创建并发布第一个版本
            ConfigVersion draft1 = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{\"v\":1}", "v1");
            service.validate(draft1.versionId());
            ConfigVersion active1 = service.publish(draft1.versionId(), "admin");

            // 创建并发布第二个版本
            ConfigVersion draft2 = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{\"v\":2}", "v2");
            service.validate(draft2.versionId());
            ConfigVersion active2 = service.publish(draft2.versionId(), "admin");

            // 验证旧版本已归档
            ConfigVersion oldVersion = service.getVersion(active1.versionId());
            assertThat(oldVersion.status()).isEqualTo(ConfigStatus.ARCHIVED);

            // 验证新版本生效
            assertThat(active2.status()).isEqualTo(ConfigStatus.ACTIVE);

            // 验证 getActive 返回新版本
            Optional<ConfigVersion> current = service.getActive(ConfigType.STRATEGY, "MacdCross");
            assertThat(current).isPresent();
            assertThat(current.get().versionId()).isEqualTo(active2.versionId());
        }

        @Test
        @DisplayName("非 VALIDATED 状态不能发布")
        void shouldRejectPublishNonValidated() {
            ConfigVersion draft = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{}", "");

            assertThatThrownBy(() -> service.publish(draft.versionId(), "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("publish")
                    .hasMessageContaining("draft");
        }

        @Test
        @DisplayName("发布后触发 ConfigPublishedEvent")
        void shouldPublishEventAfterPublish() {
            ConfigVersion draft = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{}", "");
            service.validate(draft.versionId());
            service.publish(draft.versionId(), "admin");

            ArgumentCaptor<ConfigPublishedEvent> captor = ArgumentCaptor.forClass(ConfigPublishedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            ConfigPublishedEvent event = captor.getValue();
            assertThat(event.getConfigType()).isEqualTo(ConfigType.STRATEGY);
            assertThat(event.getConfigKey()).isEqualTo("MacdCross");
        }
    }

    @Nested
    @DisplayName("rollback")
    class Rollback {

        @Test
        @DisplayName("回滚到历史版本，目标版本变为 ACTIVE")
        void shouldRollbackToTargetVersion() {
            // 创建并发布第一个版本
            ConfigVersion draft1 = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{\"v\":1}", "v1");
            service.validate(draft1.versionId());
            ConfigVersion active1 = service.publish(draft1.versionId(), "admin");

            // 创建并发布第二个版本
            ConfigVersion draft2 = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{\"v\":2}", "v2");
            service.validate(draft2.versionId());
            service.publish(draft2.versionId(), "admin");

            // 回滚到第一个版本
            ConfigVersion rolledBack = service.rollback(
                    draft2.versionId(), active1.versionId());

            assertThat(rolledBack.status()).isEqualTo(ConfigStatus.ACTIVE);
            assertThat(rolledBack.versionId()).isEqualTo(active1.versionId());

            // 验证第二个版本变为 ROLLED_BACK
            List<ConfigVersion> history = service.getHistory(ConfigType.STRATEGY, "MacdCross");
            boolean hasRolledBack = history.stream()
                    .anyMatch(v -> v.status() == ConfigStatus.ROLLED_BACK);
            assertThat(hasRolledBack).isTrue();
        }

        @Test
        @DisplayName("回滚到不同配置项的版本抛出异常")
        void shouldRejectRollbackToDifferentConfig() {
            ConfigVersion draft1 = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{}", "");
            ConfigVersion draft2 = service.createDraft(
                    ConfigType.FACTOR, "RSI", "{}", "");

            assertThatThrownBy(() -> service.rollback(draft1.versionId(), draft2.versionId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("different config");
        }
    }

    @Nested
    @DisplayName("getActive / getHistory")
    class Query {

        @Test
        @DisplayName("无生效版本时返回空")
        void shouldReturnEmptyWhenNoActive() {
            Optional<ConfigVersion> active = service.getActive(ConfigType.STRATEGY, "MacdCross");
            assertThat(active).isEmpty();
        }

        @Test
        @DisplayName("getHistory 返回所有版本，按创建时间升序")
        void shouldReturnHistoryInOrder() throws InterruptedException {
            ConfigVersion draft1 = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{\"v\":1}", "v1");
            Thread.sleep(10);
            ConfigVersion draft2 = service.createDraft(
                    ConfigType.STRATEGY, "MacdCross", "{\"v\":2}", "v2");

            List<ConfigVersion> history = service.getHistory(ConfigType.STRATEGY, "MacdCross");
            assertThat(history).hasSize(2);
            assertThat(history.get(0).versionId()).isEqualTo(draft1.versionId());
            assertThat(history.get(1).versionId()).isEqualTo(draft2.versionId());
        }

        @Test
        @DisplayName("getActiveByType 返回指定类型下所有生效版本")
        void shouldReturnActiveByType() {
            ConfigVersion d1 = service.createDraft(ConfigType.STRATEGY, "MacdCross", "{}", "");
            service.validate(d1.versionId());
            service.publish(d1.versionId(), "admin");

            ConfigVersion d2 = service.createDraft(ConfigType.STRATEGY, "RsiDivergence", "{}", "");
            service.validate(d2.versionId());
            service.publish(d2.versionId(), "admin");

            ConfigVersion d3 = service.createDraft(ConfigType.FACTOR, "RSI", "{}", "");
            service.validate(d3.versionId());
            service.publish(d3.versionId(), "admin");

            List<ConfigVersion> strategyActives = service.getActiveByType(ConfigType.STRATEGY);
            assertThat(strategyActives).hasSize(2);

            List<ConfigVersion> factorActives = service.getActiveByType(ConfigType.FACTOR);
            assertThat(factorActives).hasSize(1);
        }
    }

    @Nested
    @DisplayName("完整生命周期")
    class FullLifecycle {

        @Test
        @DisplayName("Draft → Validated → Published → Active → RolledBack 完整流程")
        void shouldSupportFullLifecycle() {
            // Draft
            ConfigVersion draft = service.createDraft(
                    ConfigType.RISK, "maxLoss",
                    "{\"maxLossPct\":2.0}", "初始风控");
            assertThat(draft.status()).isEqualTo(ConfigStatus.DRAFT);

            // Validated
            ConfigVersion validated = service.validate(draft.versionId());
            assertThat(validated.status()).isEqualTo(ConfigStatus.VALIDATED);

            // Published → Active
            ConfigVersion active = service.publish(validated.versionId(), "risk-admin");
            assertThat(active.status()).isEqualTo(ConfigStatus.ACTIVE);
            assertThat(active.publishedBy()).isEqualTo("risk-admin");

            // 验证生效
            Optional<ConfigVersion> current = service.getActive(ConfigType.RISK, "maxLoss");
            assertThat(current).isPresent();
            assertThat(current.get().contentJson()).isEqualTo("{\"maxLossPct\":2.0}");

            // 创建新版本并发布（触发旧版本归档）
            ConfigVersion draft2 = service.createDraft(
                    ConfigType.RISK, "maxLoss",
                    "{\"maxLossPct\":3.0}", "放宽风控");
            service.validate(draft2.versionId());
            ConfigVersion active2 = service.publish(draft2.versionId(), "risk-admin");

            // 回滚到第一个版本
            ConfigVersion rolledBack = service.rollback(active2.versionId(), active.versionId());
            assertThat(rolledBack.status()).isEqualTo(ConfigStatus.ACTIVE);
            assertThat(rolledBack.contentJson()).isEqualTo("{\"maxLossPct\":2.0}");
        }
    }
}
