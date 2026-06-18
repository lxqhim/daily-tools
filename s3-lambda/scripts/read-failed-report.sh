#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  read-failed-report.sh [options] s3://REPORT_BUCKET/path/to/manifest.json

Reads S3 Batch Operations completion report failed rows.

Options:
  --output FILE             Write full failed rows CSV to FILE instead of stdout.
  --summary                 Print failure summary instead of full failed rows.
  --retry-manifest FILE     Write a Batch Operations retry manifest CSV.
  --omit-version-id         Retry manifest uses Bucket,Key instead of Bucket,Key,VersionId.
  --with-header             Include a CSV header in full failed rows output.
  --keep-temp               Keep downloaded report files for debugging.
  --self-test               Run Python parser self-tests.
  -h, --help                Show this help.

Notes:
  - Requires aws CLI and python3.
  - The input is the top-level completion report manifest.json, not a result CSV.
  - Failed result CSV rows are parsed with Python csv.reader; commas and quotes in
    ResultMessage are safe.
  - Retry manifest object keys are URL-encoded as required by S3 Batch Operations
    manual CSV manifests.
EOF
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
python_script="${script_dir}/read_failed_report.py"

summary=false
with_header=false
keep_temp=false
omit_version_id=false
output_file=""
retry_manifest=""
report_manifest=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      output_file="${2:-}"
      [[ -n "$output_file" ]] || { echo "Missing value for --output" >&2; exit 2; }
      shift 2
      ;;
    --summary)
      summary=true
      shift
      ;;
    --retry-manifest)
      retry_manifest="${2:-}"
      [[ -n "$retry_manifest" ]] || { echo "Missing value for --retry-manifest" >&2; exit 2; }
      shift 2
      ;;
    --omit-version-id)
      omit_version_id=true
      shift
      ;;
    --with-header)
      with_header=true
      shift
      ;;
    --keep-temp)
      keep_temp=true
      shift
      ;;
    --self-test)
      exec python3 "$python_script" --self-test
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      if [[ -n "$report_manifest" ]]; then
        echo "Only one completion report manifest is supported" >&2
        exit 2
      fi
      report_manifest="$1"
      shift
      ;;
  esac
done

if [[ -z "$report_manifest" ]]; then
  usage >&2
  exit 2
fi

case "$report_manifest" in
  s3://*) ;;
  *)
    echo "Completion report manifest must be an s3:// URI: $report_manifest" >&2
    exit 2
    ;;
esac

command -v aws >/dev/null 2>&1 || { echo "aws CLI is required" >&2; exit 127; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 127; }

tmp_root="${TMPDIR:-/tmp}"
tmp_dir="$(mktemp -d "${tmp_root%/}/s3-batch-report.XXXXXX")"
cleanup() {
  if [[ "$keep_temp" == true ]]; then
    echo "Kept temp directory: $tmp_dir" >&2
  else
    rm -rf "$tmp_dir"
  fi
}
trap cleanup EXIT

manifest_json="${tmp_dir}/manifest.json"
aws s3 cp "$report_manifest" "$manifest_json" >/dev/null

failed_list="${tmp_dir}/failed-files.tsv"
python3 "$python_script" --list-failed-files "$manifest_json" > "$failed_list"

failed_csv_args=()
index=0
while IFS=$'\t' read -r bucket key; do
  [[ -n "${bucket}${key}" ]] || continue
  index=$((index + 1))
  local_csv="${tmp_dir}/failed-${index}.csv"
  aws s3 cp "s3://${bucket}/${key}" "$local_csv" >/dev/null
  failed_csv_args+=(--failed-csv "$local_csv")
done < "$failed_list"

if [[ ${#failed_csv_args[@]} -eq 0 ]]; then
  echo "0 failed rows" >&2
  if [[ -n "$output_file" ]]; then
    : > "$output_file"
  fi
  if [[ -n "$retry_manifest" ]]; then
    : > "$retry_manifest"
  fi
  exit 0
fi

python_args=(--manifest-json "$manifest_json" "${failed_csv_args[@]}")
if [[ "$summary" == true ]]; then
  python_args+=(--summary)
fi
if [[ "$with_header" == true ]]; then
  python_args+=(--with-header)
fi
if [[ -n "$output_file" ]]; then
  python_args+=(--output "$output_file")
fi
if [[ -n "$retry_manifest" ]]; then
  python_args+=(--retry-manifest "$retry_manifest")
fi
if [[ "$omit_version_id" == true ]]; then
  python_args+=(--omit-version-id)
fi

python3 "$python_script" "${python_args[@]}"
