package com.teamflow.project.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * M14 项目管理模块的 Mapper 扫描配置。
 *
 * <p>本模块代码并入 {@code teamflow-system} 的子包 {@code com.teamflow.project}（L0 §6.2 允许的落地方式），
 * 不新增 Maven 模块，六模块构建结构保持不变；Mapper 扫描在此显式声明，不改动启动类配置。</p>
 *
 * <p><b>边界</b>：本模块 Mapper 只访问自有 {@code proj_*} 表与平台只读表 {@code sys_user}/{@code sys_dept}；
 * 目标标题/进度经 M02 Service 读取，里程碑完成度的任务数据经 M03 Service 读取（不直连对方业务表）。</p>
 */
@Configuration
@MapperScan("com.teamflow.project.mapper")
public class ProjectMapperConfig {
}
