# Python Research Worker

隔离的 Python 研究平面 PoC。它读取带 SHA-256 的本地 Parquet manifest，执行确定性
研究并输出内容寻址报告；没有网络、数据库、OMS、券商、交易所或密钥能力。

```powershell
python -m pip install -r requirements.lock
$env:PYTHONPATH="src"
python -m unittest discover -s tests -v
crypto-research-worker path/to/job.json --output path/to/report.json
```

Java 仍是账户、资本、风险、OMS、账本和最终执行的唯一权威。Python 输出中的
`executionAuthorized` 永远为 `false`。
