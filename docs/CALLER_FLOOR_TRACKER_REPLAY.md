# Caller floor tracker: implementation and same-dataset replay

Implemented November 17, 2026, at the user's request. **Use the complete caller database and check against those same recordings.** There is no train/test split. Results describe behavior on the construction data and do not establish accuracy on a new visit.

## 1. Algorithm

The frozen relation contains 32 caller Cell IDs and 42 unique (complete Cell identity, floor) pairs, constructed from all three recordings. No frequency, within-floor proportion, learned floor prior or probability is used.

For each current returned cell, use its observed floor set. Within the preceding **60 seconds**, find floor sequences compatible with the known caller cells that minimize the number of floor changes:

- Remaining on a floor costs 0; every floor change costs 1.
- A change can jump any number of floors, so elevators remain possible.
- All starting floors permitted by the first observed cell start equally, with cost 0.
- Keep all minimum-cost current floors. One is an exact-floor result; two adjacent floors are an adjacent-pair result; all other ties produce unknown.
- Adjacency follows B1 → 1F → 2F → 3F → 4F → 5F. Nonadjacent supports remain intact inside the relation and journal.
- A returned unseen ID produces unknown and contributes no floor constraint. Known earlier observations expire normally.
- No returned Cell ID produces no prediction. Missing probes advance time and expire history.
- History resets at recording boundaries and expires by elapsed time; target labels and floor-change references never reset it.

The dynamic program stores the lowest change count for each candidate current floor. Repeated consecutive identical cells cannot increase confidence; a fresh observation can refresh history age. The change costs are heuristic preferences, not calibrated probabilities. The initial 60-second window was fixed before the first replay results, without a parameter sweep.

The current-cell-only comparison is the same rule with history disabled. It uses the same adjacent-pair/unknown output policy. Earlier frequency algorithms remain retired.

## 2. Replay inputs and scoring

Replay all 1,045 caller events chronologically, with all 512 Cell-bearing observations available as history. All their timestamps come from native first-CNI message observations. Target radio cells, altitude, itinerary and reference labels are not runtime inputs. References are joined after each prediction has been produced.

Score the 488 Cell-bearing observations with accepted B1–5F references. The other 24 Cell-bearing observations are still processed but lack usable floor references. The 533 probes without a Cell ID produce no prediction and count separately toward availability. Each scored observation is counted once; there is no fold pooling or withheld recording.

“Exact recall” is correct single-floor answers divided by all scored Cell-bearing observations, including pair answers and unknowns as unresolved. “Candidate retention” includes both correct single-floor and correct adjacent-pair answers. The primary summaries give each of the six floors equal weight. Observation-weighted totals are also shown, with B1/4F dominance kept visible.

## 3. Per-floor results on the same data

| Reference floor | Scored probes | Current cell only: correct exact | With 60 s history: correct exact | Correct adjacent pairs with history | Unknown with history |
|---|---:|---:|---:|---:|---:|
| B1 | 134 | 134/134 — 100.0% | 134/134 — 100.0% | 0 | 0 |
| 1F | 35 | 13/35 — 37.1% | 31/35 — 88.6% | 0 | 4 |
| 2F | 17 | 8/17 — 47.1% | 10/17 — 58.8% | 7 | 0 |
| 3F | 17 | 8/17 — 47.1% | 10/17 — 58.8% | 1 | 6 |
| 4F | 271 | 270/271 — 99.6% | 271/271 — 100.0% | 0 | 0 |
| 5F | 14 | 7/14 — 50.0% | 13/14 — 92.9% | 1 | 0 |
| **Equal-floor exact recall** | | **63.5%** | **83.2%** | | |

No wrong single-floor or adjacent-pair answers occurred among these 488 labeled observations. This is a same-dataset observation, not a zero-error guarantee.

| Overall result, 488 scored observations | Current cell only | With history |
|---|---:|---:|
| Correct single-floor answers | 440 | 469 |
| Correct adjacent-floor pairs | 34 | 9 |
| Wrong single/pair answers | 0 | 0 |
| Unknowns | 14 | 10 |
| Observation-weighted exact recall | 90.2% | 96.1% |
| Equal-floor candidate retention | 87.5% | 92.2% |

History changed 29 labeled outputs, resolving all 29 into the correct single floor in this replay. It added four outputs retaining the reference floor, all from previously nonadjacent ambiguity on 5F. For B1–4F alone, equal-floor exact recall is **66.2% → 81.2%**; including 5F gives the six-floor **63.5% → 83.2%** above. These are new same-dataset results and must not be compared directly with earlier held-out percentages.

### Where ambiguity remains

The 10 unknowns are four 1F and six 3F observations. They retain ties such as {1F, 3F} or {1F, 5F}. Multiple different cells sharing the same floor set can remain uninformative; the algorithm does not manufacture a winner from repeated ambiguous evidence.

The nine adjacent answers are seven on 2F, one on 3F and one on 5F. Six of the 2F answers are {1F, 2F}; the other is {2F, 3F}. The 3F pair is {2F, 3F}, and the 5F pair is {4F, 5F}.

### By recording, with history

| Recording | Scored Cell-bearing probes | Correct single floor | Correct adjacent pair | Unknown |
|---|---:|---:|---:|---:|
| nov10_afternoon | 155 | 149 | 5 | 1 |
| nov10_evening | 263 | 263 | 0 | 0 |
| nov16 | 70 | 57 | 4 | 9 |

The evening 4F-only visit contributes 263 of the 488 scored observations, explaining why the 96.1% observation-weighted result exceeds the six-floor 83.2% mean.

## 4. What this result can establish

This implements the proposed rule and shows that past caller cells can resolve some full-database ambiguity without frequency weighting. It also identifies unresolved 1F/3F and 2F cases for further development.

The database is built from the same labeled observations being checked, including later observations. Single-floor associations are therefore partly correct by construction, and static floor-support retention would be tautological on those construction rows. Runtime history itself is causal; model construction is deliberately retrospective. The comparison is useful as a behavior check, not evidence of generalization.

The minimum-change assumption can still be wrong: supports {1F, 5F} → {4F, 5F} prefer 5F → 5F, even if the person actually moved 1F → 4F. A dedicated software case verifies that such an error is counted as wrong. Accepted transition references are limited; uncertain intervals are not scored. Floors also have unequal horizontal coverage, and adjacent samples are correlated. Current references are user-supported/altitude-derived, not surveyed truth; inter-phone time alignment is not independently calibrated.

No Android app was changed or installed. This is an offline, reusable implementation ready for further algorithm work; no independent test visit or final performance claim is implied.

## 5. Reproduction and checks

```bash
python3 analysis/caller_floor_tracker.py \
  --prepared captures/floor_database_20261116/prepared.json \
  --database captures/floor_database_20261116/caller_presence_inventory/presence_database.json \
  --output captures/floor_database_20261116/caller_history_replay
python3 -m unittest discover -s analysis -p test_caller_floor_tracker.py
python3 -m unittest discover -s analysis -p test_floor_algorithm_study.py
```

- [Same-dataset result tables and configuration](../captures/floor_database_20261116/caller_history_replay/SAME_DATASET_RESULTS.json)
- [All probe events](../captures/floor_database_20261116/caller_history_replay/replay_events.jsonl), with null predictions on failed probes
- [Cell-bearing predictions only](../captures/floor_database_20261116/caller_history_replay/predictions.jsonl)
- [Database: Cell ID and all observed floors](../captures/floor_database_20261116/caller_history_replay/database.csv)
- [Every probe as a readable CSV](../captures/floor_database_20261116/caller_history_replay/detailed_results.csv)
- [Filterable spreadsheet view](../captures/floor_database_20261116/caller_history_replay/outputs/caller-details-20261118/results.xlsx)
- [Underlying caller dataset inventory](FLOOR_DATABASE_ALGORITHM_REVIEW.md)

Fourteen tracker/replay checks and ten dataset checks pass. Checks include dynamic-program agreement with exhaustive path enumeration for 256 small sequences, causal prefix behavior, elevator jumps, nonadjacent ambiguity, repeated observations, expiration and recording resets, caller-only inputs, missing-CNI exclusion, and correct counting of wrong history narrowing. Source/preparation/model hashes are recorded for reproducibility.

## 6. Per-probe result export

Every replay run now automatically writes detailed_results.csv with all 1,045
probe rows. Its first columns are **history, current Cell ID, prediction, truth
floor and result**. Additional columns include the cell alias, prediction with
history disabled, history using real Cell IDs, whether history was used,
recording, Taipei timestamp, full current-cell identity components and reference
source.

History lists earlier known cells chronologically, with their age in seconds.
The current observation is separate. The structured journals also include each
history timestamp, floor-support set and complete cell identity. History can be
retained at a failed or unseen-cell event; history_used_for_prediction marks
whether it actually contributed to an inference.

No Cell ID produces **no prediction**. A missing floor reference is **unknown**
in truth_floor and **unscored_no_floor_reference** in result. Other statuses
are exact_correct, adjacent_pair_correct, wrong and unknown. Truth is the
existing accepted reference label, with its original experimental limits.

The CLI automatically reads private_cell_mapping.json beside the prepared
observations when available. Use --cell-mapping PATH to specify another mapping.
Without a mapping, cell aliases remain usable identifiers. All exported data
remain private under captures/. The spreadsheet is a readable snapshot of the
CSV; the program-generated CSV and journals are the reproducible row outputs.

## 7. Database display

Every replay run also writes **database.csv** with one row per caller Cell ID.
The first two columns are **cell_id** and **floors**, followed by the alias and
full identity context (RAT/MCC/MNC/area). Multiple observed floors stay intact,
including nonadjacent sets. There are currently 32 rows representing 42 unique
Cell/floor pairs. This table uses the same frozen relation as inference and
never derives a frequency preference.

The spreadsheet view has a **Results** tab containing all probe rows and a
**Database** tab containing the complete Cell ID → floor(s) table. Both support
filtering. The CSV files regenerate automatically with the replay program;
the spreadsheet is a readable snapshot of their current contents.
