#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage:
  MODE=baseline MANIFEST_URI=s3://inventory-bucket/path/manifest.json ./scripts/run-migration.sh
  MODE=delta    MANIFEST_URI=s3://inventory-bucket/path/manifest.json ./scripts/run-migration.sh
  MODE=retry    RETRY_INPUTS=failed-shard-0.log ./scripts/run-migration.sh

Required environment variables:
  MODE                 baseline, delta, or retry
  REGION               AWS region, for example us-east-1
  SOURCE_BUCKET        Source S3 bucket
  TARGET_BUCKET        Target S3 bucket
  SHARD_INDEX          Current shard index, zero-based
  SHARD_TOTAL          Total shard count
  SOURCE_KMS_KEY_ID    Source client-side KMS key id or ARN for legacy decrypt

Required for baseline/delta:
  MANIFEST_URI         S3 Inventory manifest URI

Required for retry:
  RETRY_INPUTS         Comma-separated failed log paths

Optional environment variables:
  JAR_PATH             Default: target/s3-migration-v2-0.1.0-SNAPSHOT.jar
  STATE_PATH           Default: state-shard-${SHARD_INDEX}.json
  FAILED_LOG_PATH      Default: failed-shard-${SHARD_INDEX}.log
  RETRY_FAILED_LOG     Default: failed-retry-shard-${SHARD_INDEX}.log
  TEMP_DIR             Default: tmp-shard-${SHARD_INDEX}
  CONCURRENCY          Default: 8
  QUEUE_SIZE           Default: 64
  DRY_RUN              Default: false
  DRY_RUN_SAMPLE_SIZE  Default: 100
  DELTA_LOOKBACK       Default: 48h
  TARGET_CLIENT_SIDE_KMS_KEY_ID
                       Enables target client-side KMS upload with this key ARN
  EXTRA_JAVA_OPTS      Extra JVM flags, for example "-Xms4g -Xmx4g"

Run with nohup:
  nohup env MODE=baseline ... ./scripts/run-migration.sh > migration.log 2>&1 &
USAGE
}

fail() {
    echo "ERROR: $*" >&2
    echo >&2
    usage >&2
    exit 1
}

require_env() {
    local name="$1"
    if [[ -z "${!name:-}" ]]; then
        fail "$name is required"
    fi
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || "${1:-}" == "help" ]]; then
    usage
    exit 0
fi

MODE="${MODE:-${1:-}}"
case "$MODE" in
    baseline | delta | retry)
        ;;
    -h | --help | help)
        usage
        exit 0
        ;;
    "")
        fail "MODE is required"
        ;;
    *)
        fail "MODE must be baseline, delta, or retry; got '$MODE'"
        ;;
esac

require_env REGION
require_env SOURCE_BUCKET
require_env TARGET_BUCKET
require_env SHARD_INDEX
require_env SHARD_TOTAL
require_env SOURCE_KMS_KEY_ID

if [[ "$MODE" == "baseline" || "$MODE" == "delta" ]]; then
    require_env MANIFEST_URI
fi

if [[ "$MODE" == "retry" ]]; then
    require_env RETRY_INPUTS
fi

JAR_PATH="${JAR_PATH:-target/s3-migration-v2-0.1.0-SNAPSHOT.jar}"
STATE_PATH="${STATE_PATH:-state-shard-${SHARD_INDEX}.json}"
FAILED_LOG_PATH="${FAILED_LOG_PATH:-failed-shard-${SHARD_INDEX}.log}"
RETRY_FAILED_LOG="${RETRY_FAILED_LOG:-failed-retry-shard-${SHARD_INDEX}.log}"
TEMP_DIR="${TEMP_DIR:-tmp-shard-${SHARD_INDEX}}"
CONCURRENCY="${CONCURRENCY:-8}"
QUEUE_SIZE="${QUEUE_SIZE:-64}"
DRY_RUN="${DRY_RUN:-false}"
DRY_RUN_SAMPLE_SIZE="${DRY_RUN_SAMPLE_SIZE:-100}"
DELTA_LOOKBACK="${DELTA_LOOKBACK:-48h}"

if [[ ! -f "$JAR_PATH" ]]; then
    fail "JAR_PATH does not exist: $JAR_PATH"
fi

echo "Starting s3-migration-v2"
echo "  mode=$MODE"
echo "  region=$REGION"
echo "  sourceBucket=$SOURCE_BUCKET"
echo "  targetBucket=$TARGET_BUCKET"
echo "  shard=$SHARD_INDEX/$SHARD_TOTAL"
echo "  manifestUri=${MANIFEST_URI:-}"
echo "  statePath=$STATE_PATH"
echo "  failedLogPath=$FAILED_LOG_PATH"
echo "  retryInputs=${RETRY_INPUTS:-}"
echo "  retryFailedLog=$RETRY_FAILED_LOG"
echo "  tempDir=$TEMP_DIR"
echo "  concurrency=$CONCURRENCY"
echo "  queueSize=$QUEUE_SIZE"
echo "  dryRun=$DRY_RUN"
echo "  dryRunSampleSize=$DRY_RUN_SAMPLE_SIZE"
echo "  deltaLookback=$DELTA_LOOKBACK"
echo "  legacyKmsEnabled=true"
echo "  sourceKmsKeyId=$SOURCE_KMS_KEY_ID"
echo "  jarPath=$JAR_PATH"

args=(
    -jar "$JAR_PATH"
    "--migration.job.mode=$MODE"
    "--migration.s3.region=$REGION"
    "--migration.s3.source-bucket=$SOURCE_BUCKET"
    "--migration.s3.target-bucket=$TARGET_BUCKET"
    "--migration.shard.index=$SHARD_INDEX"
    "--migration.shard.total=$SHARD_TOTAL"
    "--migration.paths.state=$STATE_PATH"
    "--migration.paths.failed-log=$FAILED_LOG_PATH"
    "--migration.paths.temp-dir=$TEMP_DIR"
    "--migration.worker.concurrency=$CONCURRENCY"
    "--migration.worker.queue-size=$QUEUE_SIZE"
    "--migration.upload.dry-run=$DRY_RUN"
    "--migration.upload.dry-run-sample-size=$DRY_RUN_SAMPLE_SIZE"
    "--migration.job.delta-lookback=$DELTA_LOOKBACK"
    "--migration.decrypt.legacy-kms.enabled=true"
    "--migration.decrypt.legacy-kms.kms-key-id=$SOURCE_KMS_KEY_ID"
)

if [[ "$MODE" == "baseline" || "$MODE" == "delta" ]]; then
    args+=("--migration.inventory.manifest-uri=$MANIFEST_URI")
fi

if [[ "$MODE" == "retry" ]]; then
    args+=(
        "--migration.paths.retry-inputs=$RETRY_INPUTS"
        "--migration.paths.retry-failed-log=$RETRY_FAILED_LOG"
    )
fi

if [[ -n "${TARGET_CLIENT_SIDE_KMS_KEY_ID:-}" ]]; then
    echo "  targetClientSideKmsEnabled=true"
    echo "  targetClientSideKmsKeyId=$TARGET_CLIENT_SIDE_KMS_KEY_ID"
    args+=(
        "--migration.upload.client-side-kms.enabled=true"
        "--migration.upload.client-side-kms.kms-key-id=$TARGET_CLIENT_SIDE_KMS_KEY_ID"
    )
else
    echo "  targetClientSideKmsEnabled=false"
fi

echo "Launching java process"
exec java ${EXTRA_JAVA_OPTS:-} "${args[@]}"
