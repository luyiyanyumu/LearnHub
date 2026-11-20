ARG DOCKER_REGISTRY=docker.io
FROM ${DOCKER_REGISTRY}/library/python:3.13-slim-bookworm AS download
COPY tools/document-structure/models.py /models.py
RUN python /models.py /weights

# BuildKit can override this stage with a local directory of the five model folders.
FROM scratch AS pp-models
COPY --from=download /weights/ /

FROM ${DOCKER_REGISTRY}/library/python:3.13-slim-bookworm AS dependencies
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp \
    OMP_NUM_THREADS=1 MKL_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 \
    PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True \
    PADDLE_PDX_CACHE_HOME=/tmp/paddlex HF_HUB_OFFLINE=1
WORKDIR /app
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /tmp app
COPY tools/document-structure/requirements.txt ./
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --no-cache-dir -r requirements.txt

FROM dependencies
COPY --from=pp-models / /opt/models/
COPY tools/document-structure/models.py /app/models.py
RUN python /app/models.py --verify /opt/models
COPY tools/document-structure/engine.py tools/document-structure/server.py ./
USER 10001:10001
EXPOSE 8084
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s \
    CMD python -c "import json,urllib.request; assert json.load(urllib.request.urlopen('http://127.0.0.1:8084/health',timeout=3))['ready']"
CMD ["python", "/app/server.py"]
