from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import polars as pl

from crypto_research_worker.worker import ResearchContractError, run_research_job


class ResearchWorkerTest(unittest.TestCase):
    def test_same_manifest_produces_same_content_addressed_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._fixture(root)
            first = run_research_job(manifest)
            second = run_research_job(manifest)
            self.assertEqual(first, second)
            self.assertFalse(first["executionAuthorized"])
            self.assertEqual(len(first["reportSha256"]), 64)

    def test_dataset_tampering_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._fixture(root)
            dataset = root / "bars.parquet"
            dataset.write_bytes(dataset.read_bytes() + b"tamper")
            with self.assertRaisesRegex(ResearchContractError, "SHA-256 mismatch"):
                run_research_job(manifest)

    def test_source_has_no_network_database_or_order_dependencies(self) -> None:
        source = (Path(__file__).parents[1] / "src" / "crypto_research_worker" / "worker.py")
        text = source.read_text(encoding="utf-8")
        for forbidden in ("requests", "httpx", "socket", "sqlalchemy", "pymysql", "order_service"):
            self.assertNotIn(forbidden, text)

    def _fixture(self, root: Path) -> Path:
        dataset = root / "bars.parquet"
        rows = 12
        close = [100.0 + value + (2 if value > 5 else 0) for value in range(rows)]
        pl.DataFrame({
            "timestamp": list(range(1, rows + 1)),
            "open": [100.0 + value for value in range(rows)],
            "high": [value + 1.0 for value in close],
            "low": [99.0 + value for value in range(rows)],
            "close": close,
            "volume": [10.0] * rows,
        }).write_parquet(dataset)
        digest = hashlib.sha256(dataset.read_bytes()).hexdigest()
        manifest = root / "job.json"
        manifest.write_text(json.dumps({
            "schemaVersion": "research-job-v1",
            "dataset": {"path": "bars.parquet", "format": "parquet", "sha256": digest, "rows": rows},
            "strategy": {"id": "sma-cross", "artifactSha256": "a" * 64},
            "seed": 7,
            "parameters": {"fastWindow": 3, "slowWindow": 5},
        }), encoding="utf-8")
        return manifest


if __name__ == "__main__":
    unittest.main()
