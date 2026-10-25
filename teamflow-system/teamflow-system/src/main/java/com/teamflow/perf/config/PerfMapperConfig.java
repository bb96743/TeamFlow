package com.teamflow.perf.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * M05 绩效模块的 Mapper 扫描配置。
 *
 * <p>本模块代码并入 {@code teamflow-system} 的子包 {@code com.teamflow.perf}（L0 §6.2 允许的落地方式），
 * 不新增 Maven 模块，六模块构建结构保持不变；Mapper 扫描在此显式声明，不改动启动类配置。</p>
 */
@Configuration
@MapperScan("com.teamflow.perf.mapper")
public class PerfMapperConfig {
}
