from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

REQUIRED_COLUMNS = ("timestamp", "open", "high", "low", "close", "volume")


class ResearchContractError(ValueError):
    """Fail-closed manifest, lineage, or deterministic-input violation."""


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_sha256(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _required_text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ResearchContractError(f"{name} is required")
    return value.strip()


def _required_sha(value: Any, name: str) -> str:
    normalized = _required_text(value, name).lower()
    if len(normalized) != 64 or any(ch not in "0123456789abcdef" for ch in normalized):
        raise ResearchContractError(f"{name} must be lowercase SHA-256")
    return normalized


def run_research_job(manifest_path: str | Path) -> dict[str, Any]:
    manifest_file = Path(manifest_path).resolve(strict=True)
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != "research-job-v1":
        raise ResearchContractError("unsupported research job schema")

    dataset = manifest.get("dataset") or {}
    if dataset.get("format") != "parquet":
        raise ResearchContractError("only immutable Parquet datasets are accepted")
    dataset_path = (manifest_file.parent / _required_text(dataset.get("path"), "dataset.path")).resolve()
    if not dataset_path.is_file() or not dataset_path.is_relative_to(manifest_file.parent):
        raise ResearchContractError("dataset path must be a regular file below the manifest directory")
    declared_dataset_sha = _required_sha(dataset.get("sha256"), "dataset.sha256")
    actual_dataset_sha = _sha256_file(dataset_path)
    if actual_dataset_sha != declared_dataset_sha:
        raise ResearchContractError("dataset SHA-256 mismatch")

    strategy = manifest.get("strategy") or {}
    strategy_id = _required_text(strategy.get("id"), "strategy.id")
    strategy_sha = _required_sha(strategy.get("artifactSha256"), "strategy.artifactSha256")
    seed = manifest.get("seed")
    if not isinstance(seed, int) or seed < 0:
        raise ResearchContractError("seed must be a non-negative integer")
    parameters = manifest.get("parameters") or {}
    fast = parameters.get("fastWindow")
    slow = parameters.get("slowWindow")
    if not isinstance(fast, int) or not isinstance(slow, int) or fast < 1 or slow <= fast:
        raise ResearchContractError("SMA windows must satisfy 1 <= fast < slow")

    try:
        import polars as pl
    except ImportError as error:  # pragma: no cover - validated by packaging/CI
        raise ResearchContractError("polars runtime is not installed") from error

    frame = pl.read_parquet(dataset_path)
    missing = [column for column in REQUIRED_COLUMNS if column not in frame.columns]
    if missing:
        raise ResearchContractError(f"dataset is missing required columns: {missing}")
    declared_rows = dataset.get("rows")
    if not isinstance(declared_rows, int) or declared_rows != frame.height or frame.height < slow:
        raise ResearchContractError("dataset row count is inconsistent or insufficient")
    timestamps = frame.get_column("timestamp")
    if timestamps.null_count() or timestamps.n_unique() != frame.height or not timestamps.is_sorted():
        raise ResearchContractError("timestamps must be complete, unique, and sorted")
    for column in ("open", "high", "low", "close", "volume"):
        values = frame.get_column(column)
        if values.null_count() or values.is_nan().any() or values.is_infinite().any():
            raise ResearchContractError(f"{column} contains missing or non-finite values")
    invalid_ohlc = frame.filter(
        (pl.col("high") < pl.max_horizontal("open", "close", "low"))
        | (pl.col("low") > pl.min_horizontal("open", "close", "high"))
        | (pl.col("volume") < 0)
    )
    if invalid_ohlc.height:
        raise ResearchContractError("dataset contains invalid OHLCV rows")

    evaluated = frame.with_columns(
        pl.col("close").rolling_mean(window_size=fast).alias("fast"),
        pl.col("close").rolling_mean(window_size=slow).alias("slow"),
    ).with_columns(
        (pl.col("fast") > pl.col("slow")).alias("long_state")
    ).with_columns(
        (pl.col("long_state") != pl.col("long_state").shift(1)).fill_null(False).alias("transition")
    )
    signals = evaluated.filter(pl.col("transition")).select(
        "timestamp", pl.when(pl.col("long_state")).then(pl.lit("BUY")).otherwise(pl.lit("SELL")).alias("type")
    ).to_dicts()

    report_body = {
        "schemaVersion": "research-report-v1",
        "executionAuthorized": False,
        "datasetSha256": actual_dataset_sha,
        "manifestSha256": _canonical_sha256(manifest),
        "strategyId": strategy_id,
        "strategyArtifactSha256": strategy_sha,
        "seed": seed,
        "parameters": {"fastWindow": fast, "slowWindow": slow},
        "rows": frame.height,
        "signals": signals,
    }
    return {**report_body, "reportSha256": _canonical_sha256(report_body)}
