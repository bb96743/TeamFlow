package com.teamflow.integration.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * M12 集成与开放模块的 Mapper 扫描配置。
 *
 * <p>本模块只访问自有 {@code open_*} 表与平台只读表（用户/菜单权限）；
 * 业务数据一律经对应模块的 Service 获取，不直连其它模块的业务表。</p>
 */
@Configuration
@MapperScan("com.teamflow.integration.mapper")
public class IntegrationMapperConfig {
}
