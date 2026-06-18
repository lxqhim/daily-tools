#!/usr/bin/env python3
import argparse
import csv
import io
import json
import sys
import tempfile
import textwrap
import unittest
from collections import Counter
from pathlib import Path
from urllib.parse import quote


REPORT_FIELDS = [
    "Bucket",
    "Key",
    "VersionId",
    "TaskStatus",
    "ErrorCode",
    "HTTPStatusCode",
    "ResultMessage",
]


def load_manifest(path):
    with open(path, "r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    if not isinstance(manifest, dict):
        raise ValueError("Completion report manifest must be a JSON object")
    return manifest


def failed_report_locations(manifest):
    results = manifest.get("Results", [])
    if not isinstance(results, list):
        raise ValueError("manifest.Results must be an array")
    locations = []
    for result in results:
        if not isinstance(result, dict):
            continue
        status = str(result.get("TaskExecutionStatus", "")).lower()
        if status != "failed":
            continue
        bucket = str(result.get("Bucket", "")).strip()
        key = str(result.get("Key", "")).strip()
        if not bucket or not key:
            raise ValueError("Failed report entry must include Bucket and Key")
        locations.append((bucket, key))
    return locations


def normalize_report_row(row, source):
    if not row or all(not cell for cell in row):
        return None
    if len(row) != len(REPORT_FIELDS):
        raise ValueError(
            f"{source}: expected {len(REPORT_FIELDS)} columns "
            f"({', '.join(REPORT_FIELDS)}), got {len(row)}"
        )
    return dict(zip(REPORT_FIELDS, row))


def read_failed_rows(paths):
    rows = []
    for path in paths:
        with open(path, "r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.reader(handle)
            for row_number, row in enumerate(reader, start=1):
                normalized = normalize_report_row(row, f"{path}:{row_number}")
                if normalized is not None:
                    rows.append(normalized)
    return rows


def write_failed_rows(rows, output, with_header=False):
    writer = csv.DictWriter(output, fieldnames=REPORT_FIELDS, lineterminator="\n")
    if with_header:
        writer.writeheader()
    for row in rows:
        writer.writerow(row)


def summary_lines(rows):
    counter = Counter(
        (
            row.get("ErrorCode", "") or "<empty>",
            row.get("HTTPStatusCode", "") or "<empty>",
        )
        for row in rows
    )
    lines = ["count,error_code,http_status_code"]
    for (error_code, http_status_code), count in sorted(counter.items(), key=lambda item: (-item[1], item[0])):
        output = io.StringIO()
        csv.writer(output, lineterminator="").writerow([count, error_code, http_status_code])
        lines.append(output.getvalue())
    return lines


def encode_s3_manifest_key(key):
    return quote(key, safe="/")


def should_include_version(rows, omit_version_id):
    if omit_version_id:
        return False
    version_values = [row.get("VersionId", "") for row in rows]
    if all(version_values):
        return True
    if any(version_values):
        raise ValueError(
            "Failed rows contain a mix of blank and non-blank VersionId values. "
            "S3 Batch Operations CSV manifests must either include VersionId for all rows "
            "or omit VersionId for all rows. Re-run with --omit-version-id if latest-version "
            "retry is acceptable."
        )
    return False


def write_retry_manifest(rows, output, omit_version_id=False):
    include_version = should_include_version(rows, omit_version_id)
    writer = csv.writer(output, lineterminator="\n")
    for row in rows:
        values = [row["Bucket"], encode_s3_manifest_key(row["Key"])]
        if include_version:
            values.append(row["VersionId"])
        writer.writerow(values)


def open_output(path):
    if not path or path == "-":
        return sys.stdout, False
    return open(path, "w", encoding="utf-8", newline=""), True


def command_list_failed_files(args):
    manifest = load_manifest(args.list_failed_files)
    for bucket, key in failed_report_locations(manifest):
        print(f"{bucket}\t{key}")
    return 0


def command_read(args):
    rows = read_failed_rows(args.failed_csv)

    if args.retry_manifest:
        with open(args.retry_manifest, "w", encoding="utf-8", newline="") as handle:
            write_retry_manifest(rows, handle, omit_version_id=args.omit_version_id)
        print(f"Wrote retry manifest: {args.retry_manifest} ({len(rows)} rows)", file=sys.stderr)

    if args.summary:
        output, should_close = open_output(args.output)
        try:
            for line in summary_lines(rows):
                print(line, file=output)
        finally:
            if should_close:
                output.close()
    elif not args.retry_manifest or args.output:
        output, should_close = open_output(args.output)
        try:
            write_failed_rows(rows, output, with_header=args.with_header)
        finally:
            if should_close:
                output.close()

    return 0


class ReadFailedReportTests(unittest.TestCase):
    def test_reads_quoted_commas_and_multiline_result_message(self):
        with tempfile.TemporaryDirectory() as temp:
            report = Path(temp) / "failed.csv"
            report.write_text(
                'bucket,key-1,v1,failed,TemporaryFailure,200,"message, with comma\nand newline"\n',
                encoding="utf-8",
            )
            rows = read_failed_rows([str(report)])
        self.assertEqual(1, len(rows))
        self.assertEqual("message, with comma\nand newline", rows[0]["ResultMessage"])

    def test_retry_manifest_url_encodes_keys_and_preserves_version(self):
        rows = [
            {
                "Bucket": "source-bucket",
                "Key": "folder/a file+name.csv",
                "VersionId": "abc123",
                "TaskStatus": "failed",
                "ErrorCode": "TemporaryFailure",
                "HTTPStatusCode": "200",
                "ResultMessage": "retry me",
            }
        ]
        output = io.StringIO()
        write_retry_manifest(rows, output)
        self.assertEqual("source-bucket,folder/a%20file%2Bname.csv,abc123\n", output.getvalue())

    def test_retry_manifest_rejects_mixed_version_ids_by_default(self):
        rows = [
            {"Bucket": "b", "Key": "a", "VersionId": "v1"},
            {"Bucket": "b", "Key": "b", "VersionId": ""},
        ]
        with self.assertRaises(ValueError):
            write_retry_manifest(rows, io.StringIO())

    def test_summary_counts_by_error_code_and_http_status(self):
        rows = [
            {"ErrorCode": "TemporaryFailure", "HTTPStatusCode": "200"},
            {"ErrorCode": "TemporaryFailure", "HTTPStatusCode": "200"},
            {"ErrorCode": "PermanentFailure", "HTTPStatusCode": "403"},
        ]
        self.assertEqual(
            [
                "count,error_code,http_status_code",
                "2,TemporaryFailure,200",
                "1,PermanentFailure,403",
            ],
            summary_lines(rows),
        )

    def test_extracts_failed_locations_from_top_level_manifest(self):
        manifest = {
            "Results": [
                {"TaskExecutionStatus": "succeeded", "Bucket": "r", "Key": "ok.csv"},
                {"TaskExecutionStatus": "failed", "Bucket": "r", "Key": "failed.csv"},
            ]
        }
        self.assertEqual([("r", "failed.csv")], failed_report_locations(manifest))


def run_self_tests():
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReadFailedReportTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


def build_parser():
    parser = argparse.ArgumentParser(
        description="Read failed rows from downloaded S3 Batch Operations completion report CSV files.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent(
            """\
            Examples:
              read_failed_report.py --list-failed-files manifest.json
              read_failed_report.py --failed-csv failed.csv --summary
              read_failed_report.py --failed-csv failed.csv --retry-manifest retry.csv
            """
        ),
    )
    parser.add_argument("--manifest-json", help="Downloaded top-level completion report manifest.json")
    parser.add_argument("--list-failed-files", help="Print failed report Bucket and Key pairs as TSV")
    parser.add_argument("--failed-csv", action="append", default=[], help="Downloaded failed report CSV file")
    parser.add_argument("--output", help="Output file for full failed rows or summary; use '-' for stdout")
    parser.add_argument("--summary", action="store_true", help="Print summary by ErrorCode and HTTPStatusCode")
    parser.add_argument("--retry-manifest", help="Write Batch Operations retry manifest CSV")
    parser.add_argument("--omit-version-id", action="store_true", help="Omit VersionId from retry manifest")
    parser.add_argument("--with-header", action="store_true", help="Include header when writing full failed rows")
    parser.add_argument("--self-test", action="store_true", help="Run parser self-tests")
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_tests()
    if args.list_failed_files:
        return command_list_failed_files(args)
    if not args.failed_csv:
        parser.error("at least one --failed-csv is required unless --list-failed-files or --self-test is used")
    return command_read(args)


if __name__ == "__main__":
    sys.exit(main())
