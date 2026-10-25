package com.teamflow.system.domain.vo;

/**
 * 文件上传响应（G-1）。
 *
 * @param id           文件 ID
 * @param originalName 原始文件名（已去掉客户端路径）
 * @param size         字节数
 * @param contentType  MIME 类型
 * @param bizType      业务类型（可空）
 * @param bizId        业务对象 ID（可空）
 * @param url          下载地址（统一走鉴权接口，非静态目录直出）
 */
public record FileUploadResponse(
        Long id,
        String originalName,
        Long size,
        String contentType,
        String bizType,
        Long bizId,
        String url
) {
}
