# syntax=docker/dockerfile:1.7
# nginx image with the static UI and the reverse proxy. The build context is the repository root;
# frontend.Dockerfile.dockerignore (next to this file) limits the context to the files copied below.
#   docker build -f infra/docker/frontend.Dockerfile .

FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY infra/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY frontend/index.html frontend/styles.css /usr/share/nginx/html/
COPY frontend/js/ /usr/share/nginx/html/js/
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/healthz > /dev/null || exit 1
