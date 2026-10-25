package com.teamflow.task.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * M03 任务与工作流模块的 Mapper 扫描配置。
 *
 * <p>本模块代码并入 {@code teamflow-system} 的子包 {@code com.teamflow.task}（L0 §6.2 允许的落地方式），
 * 不新增 Maven 模块，六模块构建结构保持不变；Mapper 扫描在此显式声明，不改动启动类上的 MapperScan 配置
 * （与 M02 的 {@code OkrMapperConfig} 同构）。</p>
 */
@Configuration
@MapperScan("com.teamflow.task.mapper")
public class TaskMapperConfig {
}
