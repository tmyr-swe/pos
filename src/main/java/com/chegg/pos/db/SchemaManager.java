package com.chegg.pos.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Versioned schema migrations. V1 is loaded from the classpath and applied
 * inside a transaction (SQLite DDL is transactional).
 */
@Component
public class SchemaManager implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);
    public static final int LATEST_VERSION = 1;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public SchemaManager(DataSource dataSource,
                         JdbcTemplate jdbcTemplate,
                         TransactionTemplate transactionTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        migrate();
    }

    public void migrate() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER NOT NULL
                )
                """);

        Integer current = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM schema_version", Integer.class);

        if (current == null || current < 1) {
            log.info("Applying schema migration V1");
            transactionTemplate.executeWithoutResult(status -> {
                ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
                populator.setSqlScriptEncoding("UTF-8");
                populator.setContinueOnError(false);
                populator.addScript(new ClassPathResource("db/migrations/V1__init.sql"));
                DatabasePopulatorUtils.execute(populator, dataSource);
                jdbcTemplate.update("INSERT INTO schema_version(version) VALUES (?)", 1);
            });
        }

        Integer installed = jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM schema_version", Integer.class);
        log.info("Schema version {} ready", installed);
    }
}
