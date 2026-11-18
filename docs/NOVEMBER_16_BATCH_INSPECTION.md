# November 16 batch: extraction, inspection and next analysis

**Current scope (November 17):** combine all recordings and describe presence-only Cell/floor associations. No train/test split or accuracy calculation is active. Methods and scores below are historical; use [the current dataset analysis](FLOOR_DATABASE_ALGORITHM_REVIEW.md).

**Follow-up:** The user requested this visit become a database extension. That work is complete: both dates contribute to the B1–5F training database, both channel models are fitted on all three recordings, and presence-only lookup is now the sole active rule. See [FLOOR_DATABASE_ALGORITHM_REVIEW.md](FLOOR_DATABASE_ALGORITHM_REVIEW.md). The inspection below preserves source-quality findings; the new visit is not reserved as the final test set.

## What was collected

Both phones have been copied. Source files remain unchanged on the phones. The caller main export, caller raw capture files and target export match their phone-side SHA-256 hashes; the five research-export files also pass their manifest size/hash checks.

| Side | Actual recording coverage, Asia/Taipei | Available data |
|---|---|---|
| CellIDTracker | INVITEs 16:14:20–17:43:04 | 307 attempts; 77 Cell ID results (25.1%); 22 distinct full cell tuples |
| LocationData | Samples 16:13:21–17:43:12 | 5,350 timer samples; 345 distinct selected local cell measurements, 344 first observed with modem age at most 5 seconds |

Caller outcomes are 77 cell observed, 228 no provisional and 2 provisional without cell. All 307 exported attempt IDs match native INVITEs; every attempt has completion fields. All 77 exported primary Cell IDs match the captured first cell-bearing 183 response. The stored caller session end at 17:53 is delayed export bookkeeping, not ten additional minutes of probing.

LocationData stayed close to one sampling tick per second, with no gap above five seconds, including while the screen was off. Its 5,350 snapshots contain 2,390 distinct location fixes. Exactly one registered LTE cell was available in every radio snapshot. Local modem readings are often repeated: their median age across timer rows is 7.9 seconds, which is why distinct fresh updates are counted separately.

Neighbour records contain radio measurements but no complete neighbour Cell identities in this export. Serving Timing Advance is available for only 11 distinct modem measurements (repeated across 155 timer rows). Treat these fields as limited diagnostics rather than a complete additional positioning database.

The 1,319 complete reconstructed SIP messages pass independent contributor, timestamp and framing checks. There are no reported capture drops, sequence gaps or incomplete reconstructed streams. Preserve the native warnings for 12 fragments and 6 unsupported extensions; successful reconstruction does not prove that every possible network message was observable.

Four failed attempts have late CNI responses and 12 successful attempts have a different cell in a later 487 response. These remain separate observations for possible later analysis. The primary database continues to use one exported first-CNI Cell ID per successful probe; retransmissions are not additional independent probes.

## Floor references with minimal labeling

The exports contain no manual events or initial floor label. The user confirmed the same building as November 10 and the broad sequence B1 → 1F → 2F → 3F → 4F → 5F → return to B1. The approximate 5F visit is supported by a distinct altitude plateau around 61.9 m, with its stable envelope approximately 17:04–17:32.

Reference labels combine that confirmation with target altitude; they are not independently recorded floor truth. Retain the previous B1–4F height centers/cuts and add the 5F plateau. The primary reference excludes location age above 10 seconds, heights more than 1.25 m from their center, and intervals around height-band crossings (crossing bracket plus 10 seconds on each side). Transitions remain unknown. Altitude is a validation input only and is not available to a caller-side floor predictor.

There are 5,055 target timer rows and 289 caller opportunities with an accepted working floor reference. Sampling ticks, distinct location fixes and distinct modem measurements must be counted separately. Cached local radio readings do not imply that a successfully received caller CNI is stale.

## Usable observations by floor

These counts use the primary working reference. Caller rows are assigned using the actual captured first-CNI time, or completion time when no Cell ID was obtained. Only a target row already recorded within the preceding two seconds is used. No cross-phone clock offset was calibrated.

| Floor | Local distinct fresh cell measurements | Caller attempts with floor reference | Caller Cell ID results | Distinct caller cells |
|---|---:|---:|---:|---:|
| B1 | 52 | 44 | 15 | 4 |
| 1F | 63 | 60 | 22 | 9 |
| 2F | 23 | 19 | 2 | 1 |
| 3F | 56 | 48 | 16 | 8 |
| 4F | 29 | 25 | 1 | 1 |
| 5F | 104 | 93 | 14 | 4 |
| Total | 327 | 289 | 70 | 22 across floors |

The remaining 17 fresh local measurements and 7 caller Cell ID results lack an accepted floor reference. The 18 other caller opportunities lack a reference as well. They remain in the original data and must not be silently treated as a known floor.

## Initial findings

- All six floors have usable observations. Local data are much denser than caller data, particularly on 2F and 4F.
- Native-timed caller/local comparison yields 27 exact full-tuple matches among 77 pairs. With local effective age at most five seconds, 12 of 20 pairs match. This is source compatibility, not location accuracy; keep the two databases separate. The local freshness gate does not question the established freshness of successful caller CNI.
- Six of the 22 current caller cells were absent from the November 10 captured 183 CNI union. Identity overlap alone does not establish a matching floor association.
- Of the 22 caller cells with accepted new floor references, 17 occur on only one floor in this visit. That is an observed association, not permanent exclusivity.
- Four caller cells occur on 5F. One also occurs on 1F in this visit; another was observed on 4F in the earlier visit. Thus 5F cannot be identified by declaring every currently associated cell exclusive to 5F.
- Among local 5F cells, two previously occurred on 4F. Several local cells span more floors. Candidate floor sets and recent history are worth comparing with a single-cell lookup.
- A long 5F dwell still produced only 14 caller Cell ID results from 93 reference-eligible attempts. Missing observations must remain part of coverage and delay evaluation.

This inspection does not measure floor prediction accuracy. The mostly ascending itinerary also confounds floor with elapsed time and route. Do not infer successful floor prediction from fitting and inspecting this same visit.

## Next analysis

The original proposal to reserve the new visit was superseded by the user's database-extension request. The extension and development comparisons are now complete; follow the linked algorithm review. Both final models include B1–5F. Whole-recording/date omission folds are development diagnostics, and 5F has no independent test because it appears in only one visit.

Next, calibrate the candidate/unknown policy and test the combined database on another visit, including 5F, more 2F/3F caller observations and a different visiting order. Keep the two channel databases separate, use only past caller-visible inputs for caller inference, and report per-floor errors, answer coverage, candidate-set retention and answer age. One floor selection per stopped segment avoids per-sample typing. A matched-cadence local/caller comparison and independently referenced transition latency remain possible later analyses; they were not performed by the database-extension study.

## Private artifacts

All source captures and inspection tables remain under the ignored captures tree:

- Batch inventory and file hashes: [INVENTORY.json](../captures/batch_20261116/INVENTORY.json)
- Caller transaction audit: [CALLER_TRANSACTION_REVIEW.md](../captures/batch_20261116/CALLER_TRANSACTION_REVIEW.md)
- Capture integrity audit: [CALLER_INTEGRITY_REVIEW.md](../captures/batch_20261116/CALLER_INTEGRITY_REVIEW.md)
- Target quality audit: [TARGET_QUALITY_REVIEW.md](../captures/batch_20261116/TARGET_QUALITY_REVIEW.md)
- Confirmed retrospective references: [REFERENCE_ANCHORS.json](../captures/batch_20261116/REFERENCE_ANCHORS.json)
- Floor counts and anonymous cross-date associations: [FLOOR_INSPECTION.json](../captures/batch_20261116/inspection/FLOOR_INSPECTION.json)
- Independent count/reference review: [INDEPENDENT_FLOOR_INSPECTION_REVIEW.md](../captures/batch_20261116/INDEPENDENT_FLOOR_INSPECTION_REVIEW.md)
- Timeline: [FLOOR_CALLER_TIMELINE.png](../captures/batch_20261116/inspection/FLOOR_CALLER_TIMELINE.png)

The ordinary app export is sensitive even when stored in a directory named sanitized. Do not publish source exports, raw tuples, account identifiers, coordinates or private mappings.
