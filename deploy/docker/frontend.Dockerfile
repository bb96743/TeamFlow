# TeamFlow 前端镜像：Nginx 静态托管 dist + /api 反向代理到后端
# 构建上下文：仓库根目录（deploy/docker-compose.yml 中 context: ..）
# 前置：cd teamflow-vue && npm ci && npm run build   （产出 teamflow-vue/dist）
#
# ⚠️ 本镜像定义未经实际构建验证（开发机无 Docker）。
FROM nginx:1.27-alpine

LABEL org.opencontainers.image.title="teamflow-frontend"
LABEL org.opencontainers.image.description="TeamFlow 前端（Vue 3 静态产物 + Nginx 反代）"

# 静态产物（构建上下文为仓库根，路径形如 teamflow-vue/dist）
COPY teamflow-vue/dist /usr/share/nginx/html

# Nginx 站点配置（SPA history 路由回退 + /api 反代 + 上传大小限制）
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -qO- http://127.0.0.1/ || exit 1
