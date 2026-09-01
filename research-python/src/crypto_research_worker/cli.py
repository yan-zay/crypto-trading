from __future__ import annotations

import argparse
import json
from pathlib import Path

from .worker import run_research_job


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a credential-free immutable research job")
    parser.add_argument("manifest")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    report = run_research_job(args.manifest)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, sort_keys=True, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")


if __name__ == "__main__":
    main()
