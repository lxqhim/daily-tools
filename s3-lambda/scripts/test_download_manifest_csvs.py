import gzip
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("download_manifest_csvs.py")
SPEC = importlib.util.spec_from_file_location("download_manifest_csvs", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeBody:
    def __init__(self, value):
        self.value = value

    def read(self):
        return self.value


class FakeS3:
    def __init__(self, objects):
        self.objects = objects

    def get_object(self, Bucket, Key):
        return {"Body": FakeBody(self.objects[(Bucket, Key)])}

    def download_file(self, bucket, key, filename):
        Path(filename).write_bytes(self.objects[(bucket, key)])


class DownloadManifestCsvsTests(unittest.TestCase):
    def test_loads_s3_manifest_and_downloads_all_files(self):
        manifest = {"files": [{"key": "parts/a.csv"}, {"key": "parts/a.csv.gz"}]}
        objects = {
            ("reports", "job/manifest.json"): json.dumps(manifest).encode(),
            ("reports", "parts/a.csv"): b"one\n",
            ("reports", "parts/a.csv.gz"): gzip.compress(b"two\n"),
        }
        client = FakeS3(objects)
        loaded, bucket = MODULE.load_manifest("s3://reports/job/manifest.json", client)
        locations = MODULE.manifest_csv_locations(loaded, bucket)
        with tempfile.TemporaryDirectory() as temp:
            paths = MODULE.download_csvs(locations, Path(temp), client)
            merged = MODULE.merge_csvs(paths, Path(temp) / "all.csv")
            self.assertEqual(b"one\ntwo\n", merged.read_bytes())
            self.assertEqual(["00001-a.csv", "00002-a.csv.gz"], [path.name for path in paths])

    def test_local_inventory_manifest_uses_destination_bucket_arn(self):
        manifest = {
            "destinationBucket": "arn:aws:s3:::inventory-output",
            "files": [{"key": "inventory/data.csv.gz"}],
        }
        self.assertEqual(
            [("inventory-output", "inventory/data.csv.gz")],
            MODULE.manifest_csv_locations(manifest),
        )

    def test_explicit_csv_bucket_overrides_manifest_bucket(self):
        manifest = {"files": [{"key": "data.csv"}]}
        self.assertEqual(
            [("override", "data.csv")],
            MODULE.manifest_csv_locations(manifest, "manifest-bucket", "override"),
        )


if __name__ == "__main__":
    unittest.main()
