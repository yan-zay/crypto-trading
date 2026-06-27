package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.ConfigStatus;
import com.tj.crypto.admin.domain.ConfigType;
import com.tj.crypto.admin.domain.ConfigVersion;
import com.tj.crypto.admin.service.InMemoryConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigVersionServiceTest {

    private InMemoryConfigRepository repository;
    private ConfigVersionService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryConfigRepository();
        service = new ConfigVersionService(repository);
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
                    .hasMessageContaining("VALIDATED");
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
                    .hasMessageContaining("DRAFT");
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
            // 注意：draft2 的 ACTIVE 版本已回滚
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
            Thread.sleep(10); // 确保时间戳不同
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
            // 创建两个不同 key 的配置并发布
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
