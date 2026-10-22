package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.common.FileTextExtractedEvent;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.dyh.learnhub.mapper.KnowledgeModelOutboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real MySQL test. The caller must supply a dedicated, disposable *_model_test database. */
@EnabledIfEnvironmentVariable(named = "LEARNHUB_MODEL_TEST_JDBC_URL", matches = ".+")
class KnowledgeModelOutboxTransactionTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @Test void uploadRollbackLeavesNoRevisionAndCommittedDuplicatesSurviveServiceRestart() throws Exception {
        String url = System.getenv("LEARNHUB_MODEL_TEST_JDBC_URL");
        assertThat(url).as("Only a dedicated synthetic test database may be used")
                .matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]*_model_test(?:\\?.*)?");
        DriverManagerDataSource source = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("LEARNHUB_MODEL_TEST_USER", "root"),
                System.getenv().getOrDefault("LEARNHUB_MODEL_TEST_PASSWORD", ""));
        JdbcTemplate db = new JdbcTemplate(source);
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V5__knowledge_model_outbox.sql"));
        }
        // This test owns all rows in the explicitly named disposable database.
        db.update("DELETE FROM knowledge_model_outbox");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(KnowledgeModelOutboxMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        KnowledgeModelOutboxMapper outbox = new SqlSessionTemplate(factory.getObject()).getMapper(KnowledgeModelOutboxMapper.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.effective(SettingsService.KEY_KNOWLEDGE_MODEL_ENABLED)).thenReturn("1");
        when(settings.effective(SettingsService.KEY_KNOWLEDGE_MODEL_BASE_URL)).thenReturn("http://127.0.0.1:9");
        FileInfoMapper files = mock(FileInfoMapper.class);
        AtomicInteger afterCommit = new AtomicInteger();
        KnowledgeModelService model = new KnowledgeModelService(settings, files, outbox, new ObjectMapper()) {
            @Override public void start() { /* deterministic: tests explicitly drain the persisted queue */ }
            @Override public void afterExtractCommitted(FileTextExtractedEvent event) { afterCommit.incrementAndGet(); }
        };
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
            context.registerBean("knowledgeModelService", KnowledgeModelService.class, () -> model);
            context.refresh();
            TransactionTemplate transaction = new TransactionTemplate(manager);
            var first = new FileTextExtractedEvent(91001L, "合成第一批.txt", "合成星港识别码为 LUM-472。");
            transaction.executeWithoutResult(status -> {
                context.publishEvent(first);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_model_outbox", Integer.class)).isEqualTo(1);
                assertThat(afterCommit).hasValue(0);
                status.setRollbackOnly();
            });
            assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_model_outbox", Integer.class)).isZero();
            assertThat(afterCommit).hasValue(0);
            transaction.executeWithoutResult(status -> {
                context.publishEvent(first);
                context.publishEvent(first);
            });
            assertThat(afterCommit).hasValue(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_model_outbox", Integer.class)).isEqualTo(1);
            assertThat(outbox.findRevision(91001L, KnowledgeModelService.revision(first.text())).getTextContent())
                    .isEqualTo(first.text());
            model.drainOutbox(); // no trainer: keep the accepted revision durably retryable.
            assertThat(outbox.recent()).singleElement().satisfies(row -> {
                assertThat(row.getState()).isEqualTo("retry");
                assertThat(row.getAttempts()).isEqualTo(1);
                assertThat(row.getLastError()).isNotBlank();
            });
        }
        KnowledgeModelService restarted = new KnowledgeModelService(settings, files, outbox, new ObjectMapper());
        try {
            assertThat(restarted.jobs().get("outbox")).asList().hasSize(1);
            outbox.retry(outbox.recent().getFirst().getId());
            assertThat(outbox.nextDue()).isNotNull();
        } finally {
            restarted.stop();
            db.update("DELETE FROM knowledge_model_outbox");
        }
    }
}
