package com.teamflow.ai.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * M11 AI 助手装配：独立 Mapper 扫描 + 配置绑定（避免与其它模块的 @MapperScan 交叉）。
 */
@Configuration
@MapperScan("com.teamflow.ai.mapper")
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
}
