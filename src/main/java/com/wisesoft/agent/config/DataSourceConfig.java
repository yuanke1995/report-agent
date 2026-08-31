package com.wisesoft.agent.config;

import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.semantic.SemanticModelLoader;
import com.wisesoft.agent.semantic.SemanticModelValidator;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * 双数据源装配 + 语义层。
 * <p>
 * 业务库和智能体元数据库刻意分成两个独立数据源：
 * 元数据库（会话、审计、配置）需要读写；业务库只给只读账号，
 * 并在连接层再打一次 readOnly 标记。物理隔离比在代码里"记得不要写"可靠。
 * <p>
 * 注意：一旦手工声明了 DataSource bean，Spring Boot 的 DataSourceAutoConfiguration
 * 会整体退让（它是 @ConditionalOnMissingBean(DataSource.class)），所以元数据源
 * 也必须在这里显式声明，不能只声明业务源。
 *
 * @author yuanke
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataSourceConfig {

    private final AgentProperties properties;

    // ------------------------------------------------------------
    // 元数据库（可读写）：会话、消息、审计、配置中心
    // ------------------------------------------------------------

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties metaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties metaDataSourceProperties) {
        HikariDataSource ds = metaDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        ds.setPoolName("agent-meta");
        return ds;
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------
    // 业务库（只读）：Agent 查询的目标
    // ------------------------------------------------------------

    @Bean(name = "businessDataSource", destroyMethod = "close")
    public HikariDataSource businessDataSource() {
        AgentProperties.Business b = properties.getBusiness();
        if (b.getUrl() == null || b.getUrl().isBlank()) {
            throw new IllegalStateException("缺少必要配置：report-agent.business.url 未设置，服务拒绝启动");
        }
        if (b.getSchema() == null || b.getSchema().isBlank()) {
            throw new IllegalStateException("缺少必要配置：report-agent.business.schema 未设置，语义层无法校验");
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(b.getUrl());
        ds.setUsername(b.getUsername());
        ds.setPassword(b.getPassword());
        ds.setPoolName("business-ro");
        ds.setMaximumPoolSize(b.getMaxPoolSize());
        ds.setReadOnly(true);
        ds.setConnectionTimeout(5000);
        return ds;
    }

    @Bean(name = "businessJdbcTemplate")
    public JdbcTemplate businessJdbcTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("businessDataSource") DataSource businessDataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(businessDataSource);
        jdbc.setQueryTimeout(properties.getSqlExec().getTimeoutSeconds());
        // 兜底行数上限：SqlGuard 会强制注入 LIMIT，这里再挡一层，防止漏网的全表扫描撑爆内存
        jdbc.setMaxRows(properties.getSqlExec().getMaxRows());
        return jdbc;
    }

    /** 模板 SQL 走命名参数绑定，不做字符串拼接，因此模板路径天然免疫 SQL 注入 */
    @Bean(name = "businessNamedJdbcTemplate")
    public NamedParameterJdbcTemplate businessNamedJdbcTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate) {
        return new NamedParameterJdbcTemplate(businessJdbcTemplate);
    }

    // ------------------------------------------------------------
    // 语义层：启动时加载并与真实库结构对齐，不一致拒绝启动
    // ------------------------------------------------------------

    @Bean
    public SemanticModel semanticModel(
            @org.springframework.beans.factory.annotation.Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate) {
        SemanticModel model = new SemanticModelLoader().load();
        new SemanticModelValidator(businessJdbcTemplate, properties.getBusiness().getSchema())
                .validate(model);
        return model;
    }
}
