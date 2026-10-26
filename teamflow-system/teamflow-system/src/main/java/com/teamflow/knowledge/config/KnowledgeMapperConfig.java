package com.teamflow.knowledge.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * M07 知识与文档模块的 Mapper 扫描配置。
 *
 * <p>本模块代码并入 {@code teamflow-system} 的子包 {@code com.teamflow.knowledge}
 * （L0 §6.2 允许的落地方式），不新增 Maven 模块；Mapper 扫描在此显式声明，
 * 不改动启动类上已有的 MapperScan 配置。</p>
 */
@Configuration
@MapperScan("com.teamflow.knowledge.mapper")
public class KnowledgeMapperConfig {
}
