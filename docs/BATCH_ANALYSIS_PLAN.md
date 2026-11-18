# Analysis plan for the November 10 paired recordings

**Current scope (November 17):** combine all recordings and describe presence-only Cell/floor associations. No train/test split or accuracy calculation is active. Methods and scores below are historical; use [the current dataset analysis](FLOOR_DATABASE_ALGORITHM_REVIEW.md).

Drafted November 12, 2026. **The user subsequently authorized execution; the batch analysis is complete.** Results, limitations and next collection recommendations are in [NOVEMBER_BATCH_ANALYSIS_RESULTS.md](NOVEMBER_BATCH_ANALYSIS_RESULTS.md). The plan below preserves the intended comparisons and controls. New field collection and app changes remain separate work.

**November 16 extension:** At the user's request, the paired B1–5F visit has been added to the training database with both November 10 recordings. Both channel models now train on all available recordings. See [the combined database and algorithm review](FLOOR_DATABASE_ALGORITHM_REVIEW.md) for the current presence-only rule and [the source inspection](NOVEMBER_16_BATCH_INSPECTION.md). Previous November 10 results remain unchanged; a later visit is needed for a final test.

## Aim

Build a local location database and test whether a single complete Cell identity plus its past history can improve building/area, floor or transition-landmark inference. Compare two databases:

1. **LocationData database:** the locally selected registered Cell and its history; richer target measurements remain diagnostic or an explicitly target-assisted comparison.
2. **Caller database:** actual successful CNI observations, probe times and missing outcomes, representing the real observation channel.

Departure detection remains a backup. The immediate goal is to determine what this batch can support and identify a promising precision analysis with defensible reference labels.

## 1. What was actually collected

| Recording | Main target samples | Caller attempts | Exported CNI observations |
|---|---:|---:|---:|
| First, afternoon | 10,543 | 470 | 172 |
| Second, evening | 3,124 | 268 | 263 |

There is also a 16-sample target preflight. All data is from one day. The first caller recording has interruptions and seven native-only attempts, including two extra CNI results. Primary analysis uses the 738 exported attempts and 435 exported CNI results; recovered attempts belong in a separate sensitivity table.

The target exports contain no building/floor/point/route labels or manual events. The user confirmed there are no collection notes, and subsequently proposed reconstructing floors using recorded positions plus a few floor references or a short calibration. The user then supplied retrospective context: the first excursion was B1→2F, the later route was probably 2F→4F but uncertain, and the entire evening recording was in the same building on 4F. Preserve that distinction: **the exports are unlabeled; user-reported visit references and inferred floor intervals belong in a separate reference sidecar.**

The first target trace has several sustained altitude levels and two upward-and-return episodes. The second reports one constant altitude despite refreshed fix timestamps. The evening 4F report and subsequent confirmation of the intermediate floors support the adopted B1/1F/2F/3F/4F mapping for this batch. The first trace returns to its lower level between climbs, so do not force the uncertain later itinerary to be a continuous 2F→4F ascent.

Private evidence and inventories are under `captures/batch_20261112/`: `CALLER_INSPECTION.md`, `TARGET_INSPECTION.md`, `LABEL_INSPECTION.md`, `PAIRING_INSPECTION.md`, `ALTITUDE_LEVEL_REVIEW.md`, `FLOOR_REFERENCE_DRAFT.md` and the corresponding JSON files. Captures remain ignored by Git.

## 2. First output: a trusted paired observation table

### Preserve provenance

- Keep original files immutable and retain hashes, app/schema versions, recording boundaries and source row references.
- Make separate tables for probe attempts, CNI responses, target sampling ticks, distinct modem measurements, distinct location fixes and any derived reference intervals.
- Count one observation per successful transaction, not one per repeated 183. Preserve late responses and recovered native-only attempts separately.
- Use actual INVITE/response/sample times. The first caller's stored end extends well beyond active probing and must not create fictitious observation coverage.

### Timing and measurement quality

- Use wall timestamps to compare phones and monotonic times to check continuity within each phone. Inter-phone offset was not independently recorded; stable within-phone clocks do not prove synchronization.
- Start with zero wall-clock offset as an explicit descriptive assumption. Join each CNI response to the most recent target row within two seconds; report a five-second row-gap sensitivity. Do not optimize an offset for best Cell agreement. If needed, present predeclared ±1/±5-second offset sensitivity, not a fitted correction.
- At the response time, add target row-to-response delay to the stored modem measurement age. Report ≤5-second and ≤30-second effective-age cohorts separately, including every exclusion. A past row is not necessarily a fresh modem measurement.
- Normalize LTE to the complete RAT/MCC/MNC/area/Cell identity; exclude PCI from the caller-compatible identity. Validate unique registered selection against raw `radio.cells`.
- Preserve local missing/ambiguous readings and probe failures. Do not fill from a future sample. Bracketing future rows can diagnose transition timing but cannot become causal model inputs.
- Native steady-clock values and Android elapsed-realtime values may handle sleep differently. Their changing relative offset must not be interpreted as cross-phone wall-clock drift.

### Compatibility decision

Initial inspection already finds that exact local serving Cell and CNI do not always agree, even in the fresh subset. The ≤5-second effective-radio-age subsets agree in 23/44 afternoon pairs and 26/60 evening pairs; the ≤30-second sensitivity subsets agree in 85/172 and 78/263 pairs. These are descriptive compatibility rates under the zero-offset assumption, not location accuracy. Confirm and stratify this result before transferring a database. Shared operator/area or matching upper Cell-ID bits do not establish identical cells or a surveyed common site.

For disagreements, inspect transition proximity, local measurement age, available neighboring cells, repeated identities and subscription/registration assumptions. Neighbor matches are diagnostic; they are not proof of serving identity. If local and caller identities differ systematically, retain separate databases or learn a mapping only on calibration data. Do not force identity equality or discard disagreement cases to improve scores.

**Keep compatibility eligibility separate from positioning eligibility.** The 5/30-second local-radio gates apply to CNI-versus-modem agreement. A current caller CNI paired with a valid independent floor reference remains usable for caller-floor analysis even if Android's modem snapshot is old. Conversely, a fresh local Cell does not make an uncertain altitude-derived floor reliable. Report both cohorts and their exclusions independently.

## 3. Recover useful references with minimal manual work

### Adopted floor mapping — user follow-up

The user confirmed passing the intermediate floors and accepted the proposed mapping. **Use H0–H4 as B1/1F/2F/3F/4F for this batch's exploratory analysis.** This supersedes the earlier requirement to confirm 1F/3F before proceeding. The mapping is supported by user recall and the altitude trace; exact transition times remain height-derived, and passage through a floor does not imply a stationary dwell. Additional calibration is optional for current analysis. Independent validation remains a later stage.

### Existing altitude data first

1. Deduplicate by location-fix monotonic timestamp; inspect altitude at its fix time, not as a new reading on every radio tick. Preserve missing/stale intervals and provider-reported uncertainty.
2. Use only target height/time information to propose stable height plateaus and transition intervals. Do not use Cell identity or the eventual location model to create its own evaluation labels.
3. Use the accepted B1/1F/2F/3F/4F mapping and the same-building evening 4F reference. Construct interval times from the altitude trace, preserving uncertain transitions and the uncertain later itinerary. Ask for clarification only where it resolves a concrete ambiguity.
4. Allow uneven storey heights, basements, skipped floor names and different height offsets across visits. A formula using absolute altitude divided by a fixed floor height is not justified.
5. Repeated visits to a confirmed floor should return to a compatible height band. Test this across different horizontal positions and directions; the provider may return quantized or filtered heights. Constant altitude is not evidence that vertical changes would have been detected.
6. Export a proposed timeline with `user-reported`, `inferred`, `transition` and `unknown` status, source anchors and boundary uncertainty. The evening visit has a user-reported 4F label; the afternoon top band is inferred from it, not independently confirmed. Ask the user to confirm only unresolved segments. Keep inferred and user-reported references separate in every score. An entire user-reported floor visit does not require a fresh location fix for every labeled sample.

Android defines `getAltitude()` relative to the WGS84 ellipsoid and `getVerticalAccuracyMeters()` as an estimated 68% altitude uncertainty. The current `fused` provider and a small reported uncertainty do not independently validate floor truth. There is no raw pressure trace in these exports. [Android Location](https://developer.android.com/reference/android/location/Location), [Fused Location Provider](https://developers.google.com/location-context/fused-location-provider).

Excluding Cell features from our label-construction code prevents direct circular labeling, but it does not establish that the fused provider is independent of radio/Wi-Fi information. A score against fused-height-derived labels remains a proxy-agreement result until checked against independently identified physical floors.

### If a short calibration is needed

Use the same phone in the same building. Record a known floor, a few other known floors, and a return to the starting floor. Select a floor once per calibration segment or provide a known ordered itinerary with a few anchor confirmations; no per-sample typing or video is needed. Include movement on the same floor and upward/downward travel so horizontal-position effects and height drift can be distinguished from real floor changes.

Keep part of the known-floor calibration independent for checking inferred labels. Choose additional collection only after reviewing existing plateaus and the user's anchors. If levels overlap, fail to track actual changes, or shift on return, preserve unknowns and revise the reference method before claiming floor recognition.

### Horizontal position references

Use fresh non-mock fused fixes with declared accuracy thresholds (initial screen: ≤5-second age and ≤30-metre reported horizontal accuracy), deduplicate fixes and stratify by reported accuracy. Indoor coordinates are a noisy reference, not surveyed truth. The recorded first-session footprint is only about 194 m across; a small prediction area alone does not demonstrate useful positioning.

Do not infer building membership from a trace cluster or height. Building recognition requires independently identified buildings/areas and suitable alternative locations; confirmed building names can supply coarse visit labels but cannot manufacture precise indoor coordinates.

## 4. Describe the database before choosing a model

- Show per-visit/height-level Cell distributions, recurrence and transitions, with eligible measurement counts and durations. Keep unconfirmed height levels distinct from named floors.
- Show radio refresh cadence, caller success/absence and label/reference coverage over time. The second recording is a user-reported 4F segment; its concentrated coordinates and automatic `stationary` class do not establish a fixed horizontal point.
- Stratify caller success, missing probes, radio freshness and usable reference coverage by visit and provisional height/floor interval. The 36.6% versus 98.1% session-level caller success rates can create class/session bias and must accompany any accuracy comparison.
- Check whether a Cell occurs on several confirmed floors, whether several Cells occur at the same confirmed floor, and whether sequence differences repeat on another visit.
- For failures, tabulate target transport and recording state. Missing CNI cannot be taken as departure: initial pairing finds most failed attempts also have a target Wi-Fi context.
- Inspect route/transition candidates as patterns until endpoint/route references are available. Without independent route truth, do not score a precise transition landmark.

## 5. Precision comparisons, conditional on adequate references

### Baselines and candidates

| Comparison | Purpose |
|---|---|
| Constant training-set position / most frequent training floor | Detect gains that merely exploit the small collection area or class imbalance. |
| Single complete Cell lookup from the same database | Primary baseline for the information already present in one observation. |
| Past-window Cell set / proportions / dwell duration | Test whether repeated single-Cell observations reduce ambiguity. |
| Short ordered Cell history | Test added value of transition order over the same observation window. |
| Known-start or route/time-only tracker, separately | Test whether an apparent sequence gain comes from itinerary or starting-position knowledge. |

The caller-compatible comparisons use one complete Cell identity at a time and past history. Target neighbors, signal/Timing Advance, Wi-Fi, altitude and coordinates are excluded from those prediction inputs. Altitude may help create a calibrated target-side reference, but it is not a caller-visible feature.

First compare local single-Cell history with local single-Cell lookup. Then compare caller history with caller single-Cell lookup. Evaluate transfer from the local database separately; local serving identity and CNI agreement must be measured, not assumed. Richer local features may provide a labeled target-assisted comparison, not a caller-only result.

### Observation budgets

Simulate local observations at 10/30/60-second intervals with several start phases, drawing only the latest information already available at each simulated time and retaining missingness. Also evaluate actual caller observations at their real times. Match waiting duration, number of observations and output coverage when comparing methods; never substitute dense local sampling for the actual caller stream.

### Validation

- Do not randomly split adjacent rows. Hold out complete visits, excursions or contiguous episodes with a boundary gap at least as long as the longest feature window; deduplicate cached measurements/fixes so they do not cross the split as independent examples.
- Check reference/class overlap before a split. If only one usable visit exists for a class, state that its held-out performance is not estimable from this batch. The two recordings are not automatically an appropriate train/test pair.
- One identified building and one user-confirmed 4F visit do not validate building recognition or multi-floor classification. An evening-only floor test contains one positive class: always predicting 4F would score perfectly, so it cannot establish discrimination against other floors.
- Whole-visit same-day checks remain exploratory. Final claims require an independent date and repeated known floors/areas, including multiple horizontal positions per floor and alternative arrival routes.
- Frozen test episodes must not choose time offsets, height-to-floor mapping, thresholds or identity mappings. Separate calibration, development and test roles.
- If the evening 4F reference calibrates the altitude mapping, that same visit is not an independent test of the reference mapping. Reusing it as a test of a downstream Cell method must disclose that role and cannot validate altitude reconstruction or multi-floor discrimination.
- Score confirmed floor references separately from altitude-inferred labels. Agreement with the latter is agreement with a proxy, not independently verified floor accuracy. Hold out known-floor observations to validate the reference method itself.

### Outcomes

- Floor/area correctness and confusion, unknown/unseen-class rates, output coverage and observation delay.
- Candidate set size **and** true-reference retention; shrinking a set by removing the true floor is a failure.
- Horizontal median/tail distance to qualified recorded fixes, clearly labeled as reference discrepancy, plus performance of the constant-position baseline. Do not report indoor metre accuracy more precise than its reference supports.
- Transition-landmark false triggers and spatial/temporal spread only where independent route references exist.
- Group uncertainty by independent visit/episode. Thousands of correlated rows are not thousands of independent tests. With so few visits, emphasize episode counts and anchor/threshold sensitivities; row bootstrap intervals would imply unjustified precision.

No new geolocation requests are necessary. Existing estimates may be compared descriptively; returned service radius is not the measured positioning error.

## 6. Decision and immediate deliverables

**Order of work:** paired quality/compatibility table → altitude reconstruction with a few floor anchors → Cell distributions by usable reference → simple lookup versus history comparisons → decide targeted additional collection.

Deliver a coverage timeline, exact-identity agreement/exclusion table, proposed calibrated floor timeline with uncertainty, local/caller Cell ambiguity tables, and a clearly limited comparison when repeated labeled episodes allow it.

Proceed with a precision claim only if a history method improves on the same-database single-Cell and constant/majority baselines on held-out episodes at matched delay/coverage, and the gain survives reference-quality checks. Otherwise identify whether the limiting factor is labels, local/caller compatibility, repeated visits, observation availability or lack of Cell discrimination. Do not use the absence of current labels as a reason to discard the recordings or require manual labeling of every row.

## Review status

Independently reviewed against the actual source exports, pairing audit and retrospective references. The draft incorporates the review's requirements to distinguish reference provenance, avoid automatic floor-name transfer, separate modem and label eligibility, disclose single-class tests and report observation coverage by visit/level. No field experiment, model training, app modification or new geolocation request was performed in this phase.
