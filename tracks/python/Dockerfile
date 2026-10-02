# syntax=docker/dockerfile:1
# Build context = repository root (so the shared spec/lua scripts can be copied):
#   docker build -f tracks/python/Dockerfile -t rateguard-python .

# ---- build stage: compile wheels once, keep compilers out of the final image ----
FROM python:3.12-slim AS build
WORKDIR /build
COPY tracks/python/requirements.txt .
RUN pip wheel --no-cache-dir --wheel-dir /wheels -r requirements.txt

# ---- runtime stage ----
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PORT=8080 LUA_DIR=/app/lua
WORKDIR /app
COPY --from=build /wheels /wheels
RUN pip install --no-cache-dir /wheels/* && rm -rf /wheels \
    && useradd --system --uid 10001 --no-create-home appuser
COPY tracks/python/app ./app
COPY spec/lua ./lua
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=2).status == 200 else 1)"
CMD ["python", "-m", "app"]
