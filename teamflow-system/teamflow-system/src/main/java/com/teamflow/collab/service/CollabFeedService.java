package com.teamflow.collab.service;

import com.teamflow.collab.mapper.CollabFeedMapper;
import com.teamflow.collab.mapper.CollabPlatformMapper;
import com.teamflow.common.domain.PageQuery;
import com.teamflow.common.domain.PageResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * M09 动态流查询服务（只读）。
 *
 * <p>动态条目**只由领域事件投影产生**（见 {@link CollabFeedSubscriber}），本服务没有任何写接口；
 * 查询时只返回投影表内容，不回表业务数据。</p>
 */
@Service
public class CollabFeedService extends CollabServiceSupport {

    private final CollabFeedMapper feedMapper;

    public CollabFeedService(CollabPlatformMapper platformMapper, CollabFeedMapper feedMapper) {
        super(platformMapper);
        this.feedMapper = feedMapper;
    }

    public PageResult<Map<String, Object>> list(PageQuery query, Long actorId, String action) {
        Page page = pageOf(query);
        String actionFilter = trimToNull(action);
        long total = feedMapper.countFeeds(actorId, actionFilter);
        List<Map<String, Object>> rows = feedMapper.selectFeeds(actorId, actionFilter, page.offset(), page.pageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    private void decorate(Map<String, Object> row) {
        String action = row.get("action") == null ? null : String.valueOf(row.get("action"));
        row.put("actionLabel", actionLabel(action));
    }

    private String actionLabel(String action) {
        return switch (action == null ? "" : action) {
            case "task_completed" -> "完成";
            case "task_status_changed" -> "状态变更";
            case "task_deleted" -> "删除";
            case "task_kr_changed" -> "目标关联变更";
            default -> action;
        };
    }
}
