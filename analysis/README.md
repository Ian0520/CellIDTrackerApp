# SIP Observability Analysis

The analyzer joins caller transactions to target ground truth, selects balanced
A-B-A blocks, inventories caller-visible SIP data, and writes only aggregate or
opaque outputs. Raw phone numbers, coordinates, Call-IDs, addresses, and SIP
values are never written to the output directory.

Install dependencies if needed:

```bash
python3 -m pip install -r analysis/requirements.txt
```

Run the Day 1 analysis:

```bash
python3 analysis/sip_observability.py \
  --config analysis/day1_aba_20260922.json \
  --captures-root captures \
  --output captures/day1_aba_analysis
```

Run the Day 2 analysis:

```bash
python3 analysis/sip_observability.py \
  --config analysis/day2_aba_20260923.json \
  --captures-root captures \
  --output captures/day2_aba_analysis
```

`groundTruthConditionLabel` may be set for a block when the two apps recorded
different human-entered labels. The analyzer still requires campaign and session
timestamps to match; it does not modify either source export.

Compare both days, including the accidental unchanged-network Day 2 control:

```bash
python3 analysis/compare_sip_observability_days.py \
  --config analysis/cross_day_aba_20260923.json \
  --captures-root captures \
  --output captures/cross_day_aba_analysis
```

The cross-day output contains only aggregate statistics and equality/overlap
results. Raw SIP values and identifiers remain inside the ignored capture tree.

Run unit tests:

```bash
python3 -m unittest analysis/test_sip_observability.py
```

The experiment-specific manifest contains only session timestamps, controlled
condition labels, and network descriptions. Captures and generated outputs stay
under the ignored `captures/` directory.

## Cell sequence and one-cell location study

Use the existing seven stationary blocks as a false-change control:

```bash
python3 analysis/cell_sequence.py \
  --config analysis/cross_day_aba_20260923.json \
  --captures-root captures \
  --output captures/cell_sequence_analysis
```

The analyzer counts one fresh Call-ID per attempt, reads cell-bearing headers
from private raw captures, compares parsed cells with app exports and fresh
target modem measurements, and writes only aggregate `report.md` and
`summary.json`. Use `--max-ground-cell-age-ms 30000` for a declared sensitivity
run; the default is 5000 ms.

`analysis/cell_location_benchmark.py` fits empirical one-cell and LTE eNodeB
areas from paired caller/target sessions. It evaluates on different-day test
sessions without making API requests. Its config uses `trainSessions` and
`testSessions` arrays; each entry has `day`, `label`, `campaignId`,
`conditionLabel`, `callerSessionId`, and `groundTruthFile`. Optional settings
are `maxGroundTruthGapMs`, `maxGroundLocationAccuracyM`,
`maxGroundLocationAgeMs`, `minTrainingPointsPerCell`, and `radiusQuantile`.
Store configs containing collected session identifiers under ignored
`captures/` and run:

```bash
python3 analysis/cell_location_benchmark.py \
  --config captures/route_benchmark.json \
  --captures-root captures \
  --output captures/cell_location_benchmark
```

The benchmark reports withheld-test distance errors, radius containment, and
abstentions for unseen cells. It reports paired Google-versus-empirical scores
only where both have estimates. The collection design and current aggregate
control findings are in [`docs/CELL_SEQUENCE_STUDY.md`](../docs/CELL_SEQUENCE_STUDY.md).

```bash
python3 -m unittest analysis/test_cell_sequence.py analysis/test_cell_location_benchmark.py
```
