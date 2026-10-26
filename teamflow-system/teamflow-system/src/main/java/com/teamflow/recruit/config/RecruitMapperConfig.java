package com.teamflow.recruit.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * M08 招聘模块的 Mapper 扫描配置。
 *
 * <p>本模块代码并入 {@code teamflow-system} 的子包 {@code com.teamflow.recruit}（L0 §6.2 允许的落地方式），
 * 不新增 Maven 模块，六模块构建结构保持不变；Mapper 扫描在此显式声明，不改动启动类配置。</p>
 *
 * <p><b>边界</b>：本模块 Mapper 只访问自有 {@code recruit_*} 表与平台只读表 {@code sys_user}/{@code sys_dept}；
 * 简历文件走 M00 {@code sys_file}（只存 file_id）；流程实例复用 M03 {@code flow_*}（见 {@code RecruitFlowService}）；
 * 入职创建用户走 M00 的 {@code SystemManagementService}（不建用户表）。</p>
 */
@Configuration
@MapperScan("com.teamflow.recruit.mapper")
public class RecruitMapperConfig {
}
