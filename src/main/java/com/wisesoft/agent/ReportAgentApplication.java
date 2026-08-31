package com.wisesoft.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 企业报表智能体
 *
 * @author yuanke
 */
@SpringBootApplication
@MapperScan("com.wisesoft.agent.mapper")
public class ReportAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportAgentApplication.class, args);
    }
}
