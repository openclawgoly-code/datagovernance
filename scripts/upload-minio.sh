#!/usr/bin/env bash
# 把 data/synthetic-warehouse 上传到 MinIO 的 lake bucket。
# 依赖：MinIO compose 已启动，镜像 minio/minio 内含 mc。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NETWORK="${1:-starrocks}"
DATA_DIR="${2:-$ROOT/data/synthetic-warehouse}"
BUCKET="${MINIO_BUCKET:-lake}"
USER="${MINIO_ROOT_USER:-minioadmin}"
PASSWORD="${MINIO_ROOT_PASSWORD:-minioadmin}"

docker run --rm --network "$NETWORK" \
  -v "$DATA_DIR:/source:ro" \
  --entrypoint /bin/sh minio/minio:latest -c "
    mc alias set local http://minio:9000 '$USER' '$PASSWORD' &&
    mc mb -p local/$BUCKET &&
    mc mirror --overwrite /source local/$BUCKET/synthetic-warehouse
  "

echo "已上传到 MinIO: s3://$BUCKET/synthetic-warehouse"
