#!/usr/bin/env python3
"""Download every CSV object referenced by an S3 manifest, optionally merging them."""

import argparse
import gzip
import json
import shutil
import sys
from pathlib import Path
from urllib.parse import urlparse


def parse_s3_uri(value):
    parsed = urlparse(value)
    if parsed.scheme != "s3" or not parsed.netloc or not parsed.path.lstrip("/"):
        raise ValueError(f"Expected s3://bucket/key, got: {value}")
    return parsed.netloc, parsed.path.lstrip("/")


def bucket_name(value):
    if not value:
        return None
    if value.startswith("arn:"):
        marker = value.find(":::")
        if marker < 0 or not value[marker + 3 :]:
            raise ValueError(f"Unsupported S3 bucket ARN: {value}")
        return value[marker + 3 :]
    if value.startswith("s3://"):
        return urlparse(value).netloc
    return value


def create_s3_client(profile=None, region=None):
    try:
        import boto3
    except ImportError as exception:
        raise RuntimeError(
            "boto3 is required. Install it with: python3 -m pip install boto3"
        ) from exception
    session = boto3.Session(profile_name=profile, region_name=region)
    return session.client("s3")


def load_manifest(location, s3_client):
    if location.startswith("s3://"):
        manifest_bucket, manifest_key = parse_s3_uri(location)
        response = s3_client.get_object(Bucket=manifest_bucket, Key=manifest_key)
        raw = response["Body"].read()
        return json.loads(raw.decode("utf-8-sig")), manifest_bucket
    with open(location, "r", encoding="utf-8-sig") as handle:
        return json.load(handle), None


def manifest_csv_locations(manifest, default_bucket=None, override_bucket=None):
    if not isinstance(manifest, dict):
        raise ValueError("Manifest must be a JSON object")
    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        raise ValueError("Manifest must contain a non-empty 'files' array")

    fallback_bucket = bucket_name(override_bucket) or default_bucket or bucket_name(
        manifest.get("destinationBucket")
    )
    locations = []
    for index, entry in enumerate(files, start=1):
        if isinstance(entry, str):
            key = entry
            entry_bucket = None
        elif isinstance(entry, dict):
            key = entry.get("key") or entry.get("Key")
            entry_bucket = entry.get("bucket") or entry.get("Bucket")
        else:
            raise ValueError(f"manifest.files[{index - 1}] must be an object or string")
        if not key:
            raise ValueError(f"manifest.files[{index - 1}] does not contain a key")
        if str(key).startswith("s3://"):
            csv_bucket, csv_key = parse_s3_uri(str(key))
        else:
            csv_bucket = bucket_name(entry_bucket) or fallback_bucket
            csv_key = str(key)
        if not csv_bucket:
            raise ValueError(
                f"No bucket is available for manifest.files[{index - 1}]. "
                "Pass --csv-bucket when using a local manifest."
            )
        if not csv_key.lower().endswith((".csv", ".csv.gz", ".gz")):
            print(f"Warning: downloading non-CSV-looking key: {csv_key}", file=sys.stderr)
        locations.append((csv_bucket, csv_key))
    return locations


def safe_download_name(index, key):
    name = Path(key).name or "data.csv"
    return f"{index:05d}-{name}"


def download_csvs(locations, output_dir, s3_client, overwrite=False):
    output_dir.mkdir(parents=True, exist_ok=True)
    paths = []
    for index, (bucket, key) in enumerate(locations, start=1):
        destination = output_dir / safe_download_name(index, key)
        if destination.exists() and not overwrite:
            raise FileExistsError(f"Output already exists: {destination} (use --overwrite)")
        print(f"Downloading s3://{bucket}/{key} -> {destination}", file=sys.stderr)
        s3_client.download_file(bucket, key, str(destination))
        paths.append(destination)
    return paths


def open_csv_bytes(path):
    if path.name.lower().endswith(".gz"):
        return gzip.open(path, "rb")
    return open(path, "rb")


def merge_csvs(paths, destination, overwrite=False):
    if destination.exists() and not overwrite:
        raise FileExistsError(f"Merged output already exists: {destination} (use --overwrite)")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with open(destination, "wb") as output:
        for path in paths:
            with open_csv_bytes(path) as source:
                shutil.copyfileobj(source, output)
            # S3-generated CSV parts normally end in a newline. Add one only when needed
            # so rows from adjacent files can never be joined accidentally.
            if output.tell() > 0:
                output.flush()
                with open(destination, "rb") as check:
                    check.seek(-1, 2)
                    if check.read(1) not in (b"\n", b"\r"):
                        output.write(b"\n")
    return destination


def build_parser():
    parser = argparse.ArgumentParser(
        description="Download all CSV files listed in an S3 JSON manifest.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""Examples:
  %(prog)s s3://report-bucket/path/manifest.json -o ./manifest-csvs
  %(prog)s s3://report-bucket/path/manifest.json -o ./manifest-csvs --merge
  %(prog)s ./manifest.json --csv-bucket report-bucket --merge --merged-output ./all.csv
""",
    )
    parser.add_argument("manifest", help="Local manifest.json path or s3://bucket/key URI")
    parser.add_argument("-o", "--output-dir", default="manifest-csvs", help="Download directory")
    parser.add_argument("--csv-bucket", help="Override the bucket containing manifest CSV objects")
    parser.add_argument("--merge", action="store_true", help="Merge downloaded parts into one CSV")
    parser.add_argument(
        "--merged-output",
        help="Merged CSV path (default: <output-dir>/merged.csv); implies --merge",
    )
    parser.add_argument("--profile", help="AWS shared-config profile name")
    parser.add_argument("--region", help="AWS Region for the S3 client")
    parser.add_argument("--overwrite", action="store_true", help="Replace existing output files")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        client = create_s3_client(args.profile, args.region)
        manifest, manifest_bucket = load_manifest(args.manifest, client)
        locations = manifest_csv_locations(manifest, manifest_bucket, args.csv_bucket)
        output_dir = Path(args.output_dir)
        downloaded = download_csvs(locations, output_dir, client, args.overwrite)
        print(f"Downloaded {len(downloaded)} file(s) to {output_dir}", file=sys.stderr)
        if args.merge or args.merged_output:
            merged = Path(args.merged_output) if args.merged_output else output_dir / "merged.csv"
            merge_csvs(downloaded, merged, args.overwrite)
            print(f"Merged {len(downloaded)} file(s) into {merged}", file=sys.stderr)
        return 0
    except (ValueError, OSError, RuntimeError) as exception:
        print(f"Error: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
