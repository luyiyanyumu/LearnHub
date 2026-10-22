FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers \
    HOME=/tmp/web-fetcher-home XDG_CACHE_HOME=/tmp/web-fetcher-cache
WORKDIR /app
COPY tools/web-fetcher/requirements.txt ./
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --no-cache-dir -r requirements.txt
ARG DEBIAN_MIRROR
RUN if [ -n "$DEBIAN_MIRROR" ]; then \
        sed -i "s|http://deb.debian.org|${DEBIAN_MIRROR%/}|g" /etc/apt/sources.list.d/debian.sources; \
    fi \
    && python -m playwright install-deps chromium
ARG PLAYWRIGHT_DOWNLOAD_HOST
RUN python -m playwright install --no-shell chromium \
    && chmod -R a+rX /opt/playwright-browsers \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /tmp/web-fetcher-home app
COPY tools/web-fetcher/*.py ./
USER 10001:10001
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8082/health', timeout=5)"
CMD ["python", "/app/server.py"]
