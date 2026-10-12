ARG PYTHON_IMAGE=python:3.12-alpine
FROM ${PYTHON_IMAGE}
WORKDIR /opt/runner
COPY tools/code-runner/broker.py /opt/runner/broker.py
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8081
HEALTHCHECK --interval=20s --timeout=6s --start-period=10s --retries=3 CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8081/health', timeout=5).close()"]
ENTRYPOINT ["python", "-I", "-B", "/opt/runner/broker.py"]
