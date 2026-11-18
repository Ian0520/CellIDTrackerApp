# Caller floor dataset: construction and association analysis

Current scope, November 17, 2026: **use only caller-returned Cell IDs, combining all three recordings.** The requested history algorithm is now implemented and checked against the same complete dataset, without a train/test split. See [the same-dataset replay](CALLER_FLOOR_TRACKER_REPLAY.md) for results and their limits.

## 1. Inputs and dataset rule

Store each accepted **(caller-returned complete Cell identity, floor)** pair once. Identity includes RAT/MCC/MNC/area/Cell ID; private exports resolve aliases through the existing complete-cell mapping. The two November 10 recordings and November 16 visit all extend the same caller relation.

LocationData supplies the accepted floor references and timing context used to label caller observations. Target-side serving/neighboring cells, radio signals, altitude and Wi-Fi are not inference inputs. Local radio associations are outside the active analysis; original local records and earlier outputs remain available for later work.

Each probe contributes at most one primary first-CNI observation. Late or retransmitted responses do not become additional probes. A successful current CNI is treated as fresh, following the user's measurement premise.

Repeated observations cannot increase an existing pair's weight. Keep the full floor set, including nonadjacent supports; membership does not imply equal probabilities or physical exclusivity.

## 2. Caller inventory

| Measure | Count |
|---|---:|
| Recorded probes | 1,045 |
| Probes returning a Cell ID | 512 |
| Accepted floor-labeled Cell observations | 488 |
| Distinct labeled caller cells | 32 |
| Unique caller Cell/floor pairs | 42 |

Counts describe collection quality and volume, not weights or prediction accuracy. Missed probes and uncertain references remain in original chronology.

Accepted labels use user-supported altitude plateaus, the confirmed route and reported whole-4F evening visit. Transition/uncertain references remain unknown. These are working references, not independently surveyed truth. Cross-phone timing has no independent calibration. 5F appears in only one recording, so repeat coverage is limited.

## 3. Full caller-dataset associations

“Only here” means observed only on that floor in the current caller dataset. It does not prove that other floors are impossible. “Shared” means observed on another accepted floor as well.

| Floor | Distinct caller cells | Only observed here | Shared with another floor |
|---|---:|---:|---:|
| B1 | 6 | 6 | 0 |
| 1F | 10 | 2 | 8 |
| 2F | 8 | 3 | 5 |
| 3F | 8 | 4 | 4 |
| 4F | 6 | 5 | 1 |
| 5F | 4 | 2 | 2 |

### Relevance to the requested output types

| Observed floor support per caller cell | Number of cells |
|---|---:|
| One floor | 22 |
| Two adjacent floors | 6 |
| Two nonadjacent floors | 4 |

Adjacency follows B1 → 1F → 2F → 3F → 4F → 5F. The nonadjacent cells are C008/C009/C010 on {1F, 3F} and C031 on {1F, 5F}. These associations stay in the database; an eventual adjacent-only display policy must not remove them or substitute a different floor.

Examples: C015 maps to {4F}; C011 to {1F, 2F}; C031 to {1F, 5F}; C032 to {4F, 5F}. The private analysis lists every cell, its complete floor support, the floor-overlap count matrix and which recordings support each pair. Provenance is descriptive and is not a frequency or confidence weight.

## 4. Algorithm development direction

The requested goal is to infer a single floor, or preferably at most two adjacent floors, from the current caller Cell ID and possibly its history.

The presence-only relation remains the foundation. The implemented tracker consumes the current caller cell and preceding 60 seconds of caller observations, using no frequency weights. It emits one floor, two adjacent tied floors, or unknown; failed probes produce no prediction.

The history tracker uses dynamic programming to minimize the number of floor changes: staying costs 0 and any floor jump costs 1. History expires after 60 seconds and resets at recording boundaries; target measurements cannot update or reset it. The window is an initial fixed setting and the fewer-changes preference remains a heuristic. Dataset inventory still runs separately without scoring; the new replay script performs the requested same-dataset behavior check.

## 5. Interpretation and next work

- Inspect the full caller support sets, adjacent/nonadjacent ambiguities and recording provenance.
- Continue developing the current-cell/history algorithm using only caller-returned Cell IDs and their observation times.
- New caller recordings extend the same dataset. Broader sections and repeated visits may reveal missing pairs; equal stay times are unnecessary.
- Keep accepted target-side floor references for labeling and quality review. Local cells cannot fill missing caller observations or add caller associations.

Same-dataset replay is now active; no observations are withheld. Six-floor equal-floor exact recall is 63.5% with history disabled and 83.2% with history enabled; the full report separates exact, pair and unknown outcomes. These construction-data scores do not establish new-visit accuracy. Longer collection may reveal additional associations, and label errors may add false ones. Current single-floor supports are provisional. No live floor-prediction interface is implemented.

## 6. Active artifacts and checks

- [Implemented tracker and same-dataset results](CALLER_FLOOR_TRACKER_REPLAY.md)
- [Caller presence database](../captures/floor_database_20261116/caller_presence_inventory/presence_database.json)
- [Caller association analysis](../captures/floor_database_20261116/caller_presence_inventory/association_analysis.json)
- [Caller prepared observations](../captures/floor_database_20261116/prepared.json)
- [Manifest](../captures/floor_database_20261116/DATASET_MANIFEST.json) and [current pointer](../captures/floor_database_current.json)

Ten dataset checks and twelve tracker/replay checks pass, including exclusion of local streams, rejection of local-only inputs, duplicate invariance, full support sets, all-recording inclusion and overlap/provenance consistency. These verify pipeline behavior, not prediction accuracy. The caller relation and raw recordings are unchanged; earlier local and validation outputs are preserved outside the active workflow.
