package com.teamflow.system.domain.vo;

import org.springframework.core.io.Resource;

/**
 * 文件下载结果（G-1）：供 Controller 组装响应头与响应体。
 *
 * @param resource    磁盘资源（由 Service 完成路径安全校验后返回）
 * @param filename    原始文件名（用于 Content-Disposition）
 * @param contentType MIME 类型
 * @param size        字节数
 */
public record FileDownload(Resource resource, String filename, String contentType, long size) {
}
