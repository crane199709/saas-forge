FROM nginx:1.28-alpine
ARG SHELL_APP
COPY frontend/apps/${SHELL_APP}/dist/ /usr/share/nginx/html/
COPY deploy/images/shell-entrypoint.sh /docker-entrypoint.d/40-forge-config.sh
RUN chmod +x /docker-entrypoint.d/40-forge-config.sh
EXPOSE 8080
