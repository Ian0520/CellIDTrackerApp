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
