package com.chegg.pos.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plain Spring Framework configuration. Deliberately no Spring Boot:
 * no auto-configuration, no embedded server, no spring-boot-* artifacts.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan("com.chegg.pos")
@PropertySource("classpath:application.properties")
@EnableTransactionManagement(proxyTargetClass = true)
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public DataSource dataSource() {
        AppPaths.ensureDirectories();
        String dbPath = AppPaths.db().toString();
        log.info("Opening SQLite database at {}", dbPath);

        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        return dataSource;
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /** Single writer thread for database work; keeps the Swing EDT free. */
    @Bean(name = "dbExecutor", destroyMethod = "shutdown")
    public ExecutorService dbExecutor() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pos-db-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
