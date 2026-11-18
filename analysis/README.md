# SIP Observability Analysis

## Caller floor dataset: combine and analyze all recordings

**Current phase: caller-only dataset construction and same-dataset history replay.**
All three caller recordings extend the same database. No training, validation or
test partition is assigned. Local radio cells are excluded from the active
preparation and analysis. Target-side floor references still label caller
observations; they are not inference features.

Store each (complete caller Cell identity, floor) pair once. Current-cell lookup
returns all observed floors without frequency weighting. The implemented history tracker
uses the past 60 seconds of caller cells, preferring fewer floor changes while
allowing elevator jumps. It outputs one floor, an adjacent pair or unknown.
History-disabled lookup is the same rule without past observations.

See [the caller inventory and design scope](../docs/FLOOR_DATABASE_ALGORITHM_REVIEW.md).

```bash
python3 analysis/extend_floor_database.py
python3 analysis/floor_algorithm_study.py \
  --prepared captures/floor_database_20261116/prepared.json \
  --output captures/floor_database_20261116/caller_presence_inventory
python3 -m unittest discover -s analysis -p test_floor_algorithm_study.py
```

Preparation verifies source hashes and complete-cell aliases, preserves caller
observation opportunities, and retains accepted floor labels and provenance.
The analyzer also explicitly selects the caller stream when given an older
mixed-channel preparation, preventing local associations from entering output.

Active outputs under ignored captures/floor_database_20261116:

- prepared.json and caller_observations.jsonl, the caller-only observation table;
- private_cell_mapping.json, resolving aliases to complete Cell identities;
- caller_presence_inventory/presence_database.json, the unique caller relation;
- caller_presence_inventory/association_analysis.json, inventory, observed floor
  sets, distinct-cell counts, overlap, ambiguity and recording provenance.

Replay the complete caller recordings against the same full relation:

```bash
python3 analysis/caller_floor_tracker.py \
  --prepared captures/floor_database_20261116/prepared.json \
  --database captures/floor_database_20261116/caller_presence_inventory/presence_database.json \
  --output captures/floor_database_20261116/caller_history_replay
python3 -m unittest discover -s analysis -p test_caller_floor_tracker.py
```

The fixed initial history window is 60 seconds. Runtime replay is causal and
never reads target floor labels, local cells or future observations. The frozen
relation deliberately uses all accepted labels, including future observations;
these are same-dataset diagnostics and do not measure generalization. No data
split or parameter sweep is performed. Missing-CNI probes remain availability
only; candidate/unknown answers are unresolved for exact-floor recall.

Outputs under caller_history_replay are SAME_DATASET_RESULTS.json,
replay_events.jsonl (every probe), predictions.jsonl (Cell-bearing probes
only), detailed_results.csv (all probes in a readable table), and database.csv
(one row per caller Cell ID with its full observed floor set). The report separates per-floor equal-weight results, observation-weighted
scores, adjacent pairs, unknowns and the changes introduced by history.
Each detail row includes the earlier Cell ID history and ages, the current
Cell ID, prediction, reference floor and outcome. Actual identities are resolved
from private_cell_mapping.json beside the preparation, or --cell-mapping PATH.
Structured journals retain aliases plus full identities and history timestamps.
No returned cell is marked no prediction; unavailable floor references are
marked unknown and remain unscored. The export does not change inference.
See [the implementation and results](../docs/CALLER_FLOOR_TRACKER_REPLAY.md).

The current pointer selects these caller dataset and replay artifacts. Existing local
observations, mixed-channel outputs and earlier validation directories remain
preserved for later reference and are not active inputs/results. Generated
outputs are private. Observation counts never weight a pair.

Earlier algorithms are in archive/retired_floor_algorithms; deferred validation
sources are in archive/deferred_floor_validation. These remain outside active
imports, commands and software-check discovery.

## Earlier SIP observability studies

The following workflow describes the earlier SIP observability research, not an
additional active floor algorithm.

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
