package com.teamflow.personal.service;

import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import com.teamflow.common.exception.BusinessException;
import com.teamflow.personal.domain.dto.ReviewLogWriteRequest;
import com.teamflow.personal.mapper.PersonalPlatformMapper;
import com.teamflow.personal.mapper.ReviewLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M10 复盘与成长日志服务：周复盘默认私有存本模块（{@code review_log}）。
 *
 * <p>公开归档 M07 属后续阶段（M07 未开工）：本期只预留 {@code visibility} / {@code kb_page_id} 字段，
 * 不做跨模块写入，避免"正文复制"。</p>
 */
@Service
public class ReviewLogService extends PersonalServiceSupport {

    private static final Set<String> REVIEW_TYPES = Set.of("day", "week", "month", "custom");

    private final ReviewLogMapper reviewLogMapper;

    public ReviewLogService(PersonalPlatformMapper platformMapper, ReviewLogMapper reviewLogMapper) {
        super(platformMapper);
        this.reviewLogMapper = reviewLogMapper;
    }

    public PageResult<Map<String, Object>> list(PageQuery query, String reviewType) {
        Page page = pageOf(query);
        Long userId = currentUserId();
        String type = trimToNull(reviewType);
        if (type != null && !REVIEW_TYPES.contains(type)) {
            throw new BusinessException("复盘类型只能为 day、week、month 或 custom");
        }
        long total = reviewLogMapper.countReviews(userId, type);
        List<Map<String, Object>> rows = reviewLogMapper.selectReviews(userId, type, page.offset(), page.pageSize());
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = reviewLogMapper.selectById(id);
        if (row == null || !currentUserId().equals(numberAsLong(row.get("userId")))) {
            throw new BusinessException("复盘记录不存在或无权访问");
        }
        return row;
    }

    @Transactional
    public Long create(ReviewLogWriteRequest request) {
        Long userId = currentUserId();
        Map<String, Object> row = buildRow(request, null);
        row.put("userId", userId);
        reviewLogMapper.insertReview(row, currentUsername());
        return numberAsLong(row.get("id"));
    }

    @Transactional
    public void update(Long id, ReviewLogWriteRequest request) {
        Map<String, Object> existing = detail(id);
        Map<String, Object> row = buildRow(request, existing);
        reviewLogMapper.updateReview(id, row, currentUsername());
    }

    @Transactional
    public void delete(Long id) {
        detail(id);
        reviewLogMapper.softDelete(id, currentUsername());
    }

    // ------------------------------------------------------------------

    private Map<String, Object> buildRow(ReviewLogWriteRequest request, Map<String, Object> existing) {
        String type = request.getReviewType() == null
                ? (existing == null ? "week" : String.valueOf(existing.get("reviewType")))
                : request.getReviewType();
        if (!REVIEW_TYPES.contains(type)) {
            throw new BusinessException("复盘类型只能为 day、week、month 或 custom");
        }
        String title = request.getReviewTitle() == null
                ? (existing == null ? null : String.valueOf(existing.get("reviewTitle")))
                : required(request.getReviewTitle(), "复盘标题不能为空");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("reviewType", type);
        row.put("reviewTitle", title);
        row.put("periodStart", request.getPeriodStart() == null
                ? (existing == null ? null : parseDate(dateText(existing.get("periodStart")), "周期开始日期"))
                : parseDate(request.getPeriodStart(), "周期开始日期"));
        row.put("periodEnd", request.getPeriodEnd() == null
                ? (existing == null ? null : parseDate(dateText(existing.get("periodEnd")), "周期结束日期"))
                : parseDate(request.getPeriodEnd(), "周期结束日期"));
        row.put("content", request.getContent() == null
                ? (existing == null ? null : existing.get("content")) : request.getContent());
        row.put("tags", request.getTags() == null
                ? (existing == null ? null : existing.get("tags")) : trimToNull(request.getTags()));
        row.put("visibility", existing == null ? "private" : String.valueOf(existing.get("visibility")));
        return row;
    }
}
