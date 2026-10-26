package com.teamflow.insight.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * M13 统计与洞察模块的 Mapper 扫描配置。
 *
 * <p>本模块代码并入 {@code teamflow-system} 的子包 {@code com.teamflow.insight}（L0 §6.2 允许的落地方式），
 * 不新增 Maven 模块，六模块构建结构保持不变；Mapper 扫描在此显式声明，不改动启动类配置。
 * 本模块的 Mapper **只访问 {@code insight_metric}（指标字典）与 {@code sys_user}/{@code sys_dept}（平台只读引用）**，
 * 业务事实数据一律经 M02/M03/M04 的 Service 获取（L0 §4.3、M13 RULE §四）。</p>
 */
@Configuration
@MapperScan("com.teamflow.insight.mapper")
public class InsightMapperConfig {
}
