package com.teamflow.integration.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.integration.config.IntegrationProperties;
import com.teamflow.integration.domain.dto.IntegrationAppWriteRequest;
import com.teamflow.integration.mapper.OpenAppMapper;
import com.teamflow.integration.security.CredentialCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第三方集成中心服务（2.4.1）：接入配置、凭据加密存储、连通性检测框架。
 *
 * <p><b>诚实边界（D-2026-10-27-12 结论六）</b>：本机无任何第三方 SaaS 凭据、无外网。
 * 因此本模块<b>不做也不声称</b>钉钉/企微/飞书/GitHub/Jira 的真实业务调用；
 * 只提供「配置 + 凭据加密 + 连通性探测」框架：连通性检测对配置的地址发起一次真实 HTTP 探测，
 * 无凭据、无地址、网络不可达时都会<b>明确返回失败</b>，绝不伪造成功。</p>
 */
@Service
public class IntegrationAppService extends IntegrationSupport {

    private static final Set<String> APP_TYPES = Set.of(
            "dingtalk", "wecom", "feishu", "github", "jira", "open_client");
    private static final Set<String> STATUSES = Set.of("enabled", "disabled");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OpenAppMapper appMapper;
    private final CredentialCipher credentialCipher;
    private final IntegrationProperties properties;

    public IntegrationAppService(
            OpenAppMapper appMapper,
            CredentialCipher credentialCipher,
            IntegrationProperties properties
    ) {
        this.appMapper = appMapper;
        this.credentialCipher = credentialCipher;
        this.properties = properties;
    }

    public PageResult<Map<String, Object>> list(PageQuery query, String keyword, String appType, String status) {
        Page page = pageOf(query);
        String typeFilter = trimToNull(appType);
        String statusFilter = trimToNull(status);
        long total = appMapper.countApps(trimToNull(keyword), typeFilter, statusFilter);
        List<Map<String, Object>> rows = appMapper.selectApps(trimToNull(keyword), typeFilter, statusFilter,
                page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = appMapper.selectById(id);
        if (row == null) {
            throw new BusinessException("接入应用不存在");
        }
        decorate(row);
        return row;
    }

    /** 凭据加密能力状态（不泄露密钥；供页面提示"配置凭据前需先配置密钥"）。 */
    public Map<String, Object> encryptionStatus() {
        boolean configured = credentialCipher.configured();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("credentialEncryptionConfigured", configured);
        status.put("keyFingerprint", configured ? credentialCipher.keyFingerprint() : null);
        status.put("hint", configured
                ? "凭据将以对称加密存储；明文不落库、不回读、不写日志"
                : "未配置 TEAMFLOW_INTEGRATION_CREDENTIAL_KEY，保存凭据会被拒绝（不会以明文存储）");
        return status;
    }

    @Transactional
    public Long create(IntegrationAppWriteRequest request) {
        if (request == null) {
            throw new BusinessException("接入应用请求不能为空");
        }
        String code = required(request.appCode(), "应用编码不能为空");
        if (appMapper.selectIdByCode(code, null) != null) {
            throw new BusinessException("应用编码已存在：" + code);
        }
        String name = required(request.appName(), "应用名称不能为空");
        String appType = defaultString(request.appType(), "open_client");
        if (!APP_TYPES.contains(appType)) {
            throw new BusinessException("接入类型只能为 dingtalk / wecom / feishu / github / jira / open_client");
        }
        String status = defaultString(request.status(), "enabled");
        if (!STATUSES.contains(status)) {
            throw new BusinessException("状态只能为 enabled 或 disabled");
        }
        String endpoint = validateEndpoint(request.endpointUrl());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("appCode", code);
        row.put("appName", name);
        row.put("appType", appType);
        row.put("endpointUrl", endpoint);
        row.put("configJson", trimToNull(request.configJson()));
        applyCredential(row, request.credential());
        row.put("status", status);
        row.put("remark", trimToNull(request.remark()));
        appMapper.insertApp(row, currentUsername());
        return numberAsLong(row.get("id"));
    }

    @Transactional
    public void update(Long id, IntegrationAppWriteRequest request) {
        Map<String, Object> existing = appMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("接入应用不存在");
        }
        String appType = request.appType() == null ? stringOf(existing.get("appType")) : request.appType();
        if (!APP_TYPES.contains(appType)) {
            throw new BusinessException("接入类型只能为 dingtalk / wecom / feishu / github / jira / open_client");
        }
        String status = request.status() == null ? stringOf(existing.get("status")) : request.status();
        if (!STATUSES.contains(status)) {
            throw new BusinessException("状态只能为 enabled 或 disabled");
        }
        String endpoint = request.endpointUrl() == null
                ? stringOf(existing.get("endpointUrl")) : validateEndpoint(request.endpointUrl());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("appName", request.appName() == null
                ? existing.get("appName") : required(request.appName(), "应用名称不能为空"));
        row.put("appType", appType);
        row.put("endpointUrl", endpoint);
        row.put("configJson", request.configJson() == null
                ? existing.get("configJson") : trimToNull(request.configJson()));
        if (trimToNull(request.credential()) == null) {
            // 凭据留空表示保持不变（避免编辑非凭据字段时覆盖原凭据）
            row.put("credentialCipher", appMapper.selectCredentialCipher(id));
            row.put("credentialHint", existing.get("credentialHint"));
        } else {
            applyCredential(row, request.credential());
        }
        row.put("status", status);
        row.put("remark", request.remark() == null ? existing.get("remark") : trimToNull(request.remark()));
        appMapper.updateApp(id, row, currentUsername());
    }

    @Transactional
    public void delete(Long id) {
        if (appMapper.selectById(id) == null) {
            throw new BusinessException("接入应用不存在");
        }
        appMapper.softDelete(id, currentUsername());
    }

    /**
     * 连通性检测：对配置的接入地址发起一次真实 HTTP 探测（超时 3 秒），并把结果落库。
     *
     * <p>不伪造：无地址、无凭据、凭据无法解密、网络不可达、非 2xx/3xx 响应都返回
     * {@code success=false} 并写明原因。第三方业务 API 的真实可用性本机无法验证（无外网/无凭据）。</p>
     */
    @Transactional
    public Map<String, Object> check(Long id) {
        Map<String, Object> app = appMapper.selectById(id);
        if (app == null) {
            throw new BusinessException("接入应用不存在");
        }
        String endpoint = stringOf(app.get("endpointUrl"));
        String cipher = appMapper.selectCredentialCipher(id);
        LocalDateTime now = LocalDateTime.now();
        String result;
        String message;
        if (endpoint == null || endpoint.isBlank()) {
            result = "failed";
            message = "未配置接入地址，无法进行连通性检测";
        } else if (cipher == null || cipher.isBlank()) {
            result = "failed";
            message = "未配置凭据，无法完成联通性检测（不会以匿名方式探测第三方服务）";
        } else {
            try {
                credentialCipher.decrypt(cipher);
            } catch (BusinessException exception) {
                result = "failed";
                message = "凭据无法解密：" + exception.getMessage();
                recordCheck(id, result, message, now);
                return checkView(id, app, endpoint, result, message, now);
            }
            ProbeResult probe = probe(endpoint);
            result = probe.success() ? "success" : "failed";
            message = probe.message();
        }
        recordCheck(id, result, message, now);
        return checkView(id, app, endpoint, result, message, now);
    }

    // ------------------------------------------------------------------

    private ProbeResult probe(String endpoint) {
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getCheckTimeoutSeconds()));
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("User-Agent", "TeamFlow-Integration-Check/1.0")
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 400) {
                return new ProbeResult(true, "接入地址可访问（HTTP " + status + "）；未验证第三方业务接口与凭据有效性");
            }
            return new ProbeResult(false, "接入地址返回 HTTP " + status + "，视为不可用");
        } catch (IllegalArgumentException exception) {
            return new ProbeResult(false, "接入地址格式不合法：" + exception.getMessage());
        } catch (Exception exception) {
            String reason = exception.getClass().getSimpleName();
            String detail = exception.getMessage() == null ? "" : "（" + exception.getMessage() + "）";
            return new ProbeResult(false, "无法连接接入地址：" + reason + detail + "；本机无外网或地址不可达时属于预期失败");
        }
    }

    private void recordCheck(Long id, String result, String message, LocalDateTime time) {
        appMapper.updateCheckResult(id, result, message, time, currentUsername());
    }

    private Map<String, Object> checkView(Long id, Map<String, Object> app, String endpoint,
                                          String result, String message, LocalDateTime time) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("appId", id);
        view.put("appCode", app.get("appCode"));
        view.put("appName", app.get("appName"));
        view.put("endpointUrl", endpoint);
        view.put("success", "success".equals(result));
        view.put("result", result);
        view.put("message", message);
        view.put("checkedAt", time.format(DATE_TIME));
        view.put("credentialConfigured", booleanOf(app.get("credentialConfigured")));
        view.put("limitation", "仅做地址可达性探测；第三方业务接口与凭据有效性未验证（本机无外网/无凭据）");
        return view;
    }

    private void applyCredential(Map<String, Object> row, String plainCredential) {
        String credential = trimToNull(plainCredential);
        if (credential == null) {
            row.put("credentialCipher", null);
            row.put("credentialHint", null);
            return;
        }
        if (!credentialCipher.configured()) {
            throw new BusinessException("凭据加密密钥未配置，拒绝保存凭据（不会以明文存储）。"
                    + "请设置环境变量 TEAMFLOW_INTEGRATION_CREDENTIAL_KEY 后重试");
        }
        row.put("credentialCipher", credentialCipher.encrypt(credential));
        row.put("credentialHint", credentialCipher.hintOf(credential));
    }

    private String validateEndpoint(String endpointUrl) {
        String endpoint = trimToNull(endpointUrl);
        if (endpoint == null) {
            return null;
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new BusinessException("接入地址必须以 http:// 或 https:// 开头");
        }
        try {
            URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("接入地址格式不合法");
        }
        return endpoint;
    }

    private void decorate(Map<String, Object> row) {
        row.put("credentialConfigured", booleanOf(row.get("credentialConfigured")));
        row.put("appTypeLabel", appTypeLabel(stringOf(row.get("appType"))));
        row.put("statusLabel", "enabled".equals(stringOf(row.get("status"))) ? "启用" : "停用");
        row.put("credentialHint", row.get("credentialHint"));
    }

    private String appTypeLabel(String appType) {
        return switch (appType == null ? "" : appType) {
            case "dingtalk" -> "钉钉";
            case "wecom" -> "企业微信";
            case "feishu" -> "飞书";
            case "github" -> "GitHub";
            case "jira" -> "Jira";
            case "open_client" -> "开放调用方";
            default -> appType;
        };
    }

    private record ProbeResult(boolean success, String message) {
    }
}
