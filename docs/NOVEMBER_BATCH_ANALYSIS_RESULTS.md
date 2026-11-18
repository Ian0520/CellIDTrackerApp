# November 10 batch: floor and location analysis

**Current scope (November 17):** combine all recordings and describe presence-only Cell/floor associations. No train/test split or accuracy calculation is active. Methods and scores below are historical; use [the current dataset analysis](FLOOR_DATABASE_ALGORITHM_REVIEW.md).

Completed **November 12, 2026**, Asia/Taipei. This is an exploratory analysis of one building and one date, using the B1/1F/2F/3F/4F correspondence accepted by the user.

## Main findings

1. **Cell identity contains useful floor-related structure in this batch.** B1 and 4F have distinct observed Cell groups; 1F and 2F share several identities. This is an association in the collected building, not proof of building recognition or a universal floor database.
2. **A short history mainly increases the number of queries receiving an answer.** In the caller holdout with all test floors represented in training, 60-second history raises answer coverage from **40.5% to 49.2%**. On the same 50 full-history cases, correct predictions change from **47 to 48**. This small difference does not establish a reliable accuracy improvement.
3. **Longer history and switching order are not clearly better.** A 120-second history makes more mistakes on those same cases. The tested ordered-transition lookup adds no benefit over single-Cell lookup in the primary caller comparison.
4. **Horizontal gain was not found on the held-out excursions.** Empirical Cell-position estimates are worse than a constant training-position baseline on matched rows. The evening single-floor check does improve fused-reference discrepancy, but does not establish surveyed indoor accuracy.

**Recommendation:** continue testing the known-building floor database with balanced, differently ordered visits. Prioritize missing support on 1F/2F/3F before adding a more complex sequence model.

## 1. Data and derived references

| Item | Afternoon | Evening |
|---|---:|---:|
| LocationData sample rows | 10,543 | 3,124 |
| Distinct selected-Cell measurement identity/timestamp pairs | 642 | 187 |
| Exported caller attempts | 470 | 268 |
| Exported CNI results | 172 | 263 |
| CNI observation rate | 36.6% | 98.1% |
| Floor reference | Height-derived, supported by user recall | User-reported 4F visit |

All 435 exported Cell tuples match the native signaling. Analysis uses actual native response-capture times, retaining the original app timestamps. One app timestamp was 477 ms earlier than the captured response; the correction is direct transaction evidence, not a fitted clock shift.

Seven native-only attempts were identified. Two additional CNI results are included only in a sensitivity analysis. Five lack established completion times, so no completion events were invented. The recovered sensitivity has 740 time-anchored rows and 437 CNI results; it is **not** the denominator of all 745 distinct native/exported attempts and must not be used as a corrected probe-yield estimate.

### Floor reconstruction

The accepted ascending height bands correspond to B1/1F/2F/3F/4F. Primary afternoon policy:

- Location-fix age at query ≤10 seconds.
- Height within 1.25 m of the declared plateau centre.
- Exclude each observed height-change bracket plus 10 seconds on both sides.
- Evening 4F remains a whole-visit user reference even where a location fix is unavailable.

The afternoon retains 9,791 reference-labeled sample rows and 752 unknown/transition/old-fix rows. Exact floor-change times remain derived from fused height. The accepted mapping makes exploratory analysis possible; these are not independently surveyed transition labels.

![Floor reference and caller availability](../captures/batch_20261112/analysis/HEIGHT_FLOOR_CALLER_TIMELINE.png)

The shaded afternoon windows are whole-excursion split units. Evening contains only 4F. Grey regions are unknown reference intervals or transition guards.

## 2. What each database contains

Counts are distinct, known-floor Cell observations after deduplication. Local counts use first-available fresh modem updates, not every repeated one-second sample. Caller counts use one successful response per exported transaction.

| Reference floor | Local observations, both recordings | Caller observations, afternoon | Caller observations, evening |
|---|---:|---:|---:|
| B1 | 519 | 119 | 0 |
| 1F | 24 | 13 | 0 |
| 2F | 38 | 15 | 0 |
| 3F | 13 | 1 | 0 |
| 4F | 217 | 7 | 263 |

In the second excursion alone, caller training support is **B1: 14, 1F: 1, 2F: 2, 3F: 1, 4F: 7**. The first excursion supplies B1/1F/2F but no 3F/4F examples. This limits both learning and evaluation.

Across all known-floor data, 9 of 29 local identities and 3 of 23 caller identities occur on multiple reference floors. Some have very small counts; an identity seen on one floor is not proven exclusive to that floor.

![Descriptive Cell and floor distributions](../captures/batch_20261112/analysis/CELL_FLOOR_HEATMAP.png)

The heatmap combines visits for description and is not held-out accuracy. Many evening 4F observations are not repeated multi-floor experiments.

### Local and caller Cells differ

With direct native response times and the latest past local reading:

| Pair | Exact agreement, local radio age ≤5 s | Exact agreement, age ≤30 s |
|---|---:|---:|
| Afternoon | 23/44 (52.3%) | 85/172 (49.4%) |
| Evening | 26/61 (42.6%) | 79/263 (30.0%) |

These update the earlier export-timestamp figures. All pairs share operator and area; most differences affect lower Cell-ID bits. No identities were merged to improve agreement. Interphone clock offset remains uncalibrated.

This is a compatibility measurement, not a location score. Local-radio freshness filtering does not discard a current caller CNI paired with a usable floor reference.

## 3. Evaluation design

- **E1 → E2:** train on the first excursion, test the whole second excursion. 3F/4F are unseen training classes.
- **E2 → E1:** reverse the split. The held-out excursion contains B1/1F/2F, all represented in training. This is the clearest available floor comparison, still from one day.
- **Afternoon → evening:** a one-class 4F stability check. Always predicting 4F would be correct throughout; it cannot establish multi-floor discrimination.
- **Local → caller transfer:** train on local identities and test actual caller identities on held-out episodes, without fitting an identity mapping.

Cell predictors use current/past observations only. Training and test have no shared cached measurement/fix IDs. Histories reset at split/episode boundaries and recording gaps. Floor labels, altitude and coordinates do not enter these predictors.

Frozen comparisons: single-Cell lookup, 60/120-second empirical Cell distributions, ordered transition lookup with single-Cell fallback, majority-floor priors, and a time-only control.

The time-only control is deliberately privileged: it knows elapsed time from the episode start, learns fixed 60-second training bins, and never uses future test duration. It diagnoses itinerary confounding and is **not** an equivalent caller-only method.

**Metrics:** coverage is the fraction of reference-labeled opportunities receiving a prediction. Overall accuracy counts abstentions as misses. Conditional accuracy uses answered opportunities. Balanced accuracy averages recall over floors present in that test. These denominators are reported together.

## 4. Main floor results

### Train E2, hold out E1

| Database / method | Correct / all labeled opportunities | Coverage | Correct among answers | Balanced accuracy, abstentions included |
|---|---:|---:|---:|---:|
| Local fresh updates: single Cell | 71/119 (59.7%) | 66.4% | 89.9% | 44.0% |
| Local fresh updates: 60-second history | 79/119 (66.4%) | 78.2% | 84.9% | 55.7% |
| Caller: single Cell | 48/126 (38.1%) | 40.5% | 94.1% | 24.6% |
| Caller: 60-second history | 58/126 (46.0%) | 49.2% | 93.5% | 38.8% |
| Caller: 120-second history | 56/126 (44.4%) | 50.0% | 88.9% | 37.3% |
| Caller: always training-majority floor | 74/126 (58.7%) | 100% | 58.7% | 33.3% |
| Caller: privileged time-only control | 66/126 (52.4%) | 100% | 52.4% | 35.7% |

The local fresh-update stream is conditioned on new modem measurements; it is not an achievable periodic caller schedule.

The caller test has 126 labeled opportunities: 70 have CNI and 56 do not. Lookup answers 51; 60-second history answers 62. History can reuse a trained Cell observed recently when the current nonnull Cell is unseen. Its main gain is fewer unknowns; it does not recover missing current CNI.

On the **same 50 caller cases** with a full window and predictions from every compared method:

| Method | Correct |
|---|---:|
| Always training-majority floor | 44/50 |
| Privileged time-only control | 34/50 |
| Single Cell | 47/50 |
| 60-second history | 48/50 |
| Ordered transition, 60 seconds | 47/50 |
| 120-second history | 45/50 |

Those cases contain **44 B1, five 1F and one 2F** references. One additional correct answer is insufficient evidence of a stable improvement. High conditional accuracy must not conceal class imbalance or low coverage.

### Opposite direction and transfer

In E1 → E2, caller lookup answers 14/120 labeled opportunities, with 13 correct. A 60-second history answers 17, with 14 correct. Training has no 3F/4F classes, and 95/120 opportunities have no current CNI. This does not demonstrate five-floor recognition.

Direct local → caller transfer on E2 → E1 gives overall accuracy **39.7% → 42.1%** and coverage **42.1% → 46.8%** from single Cell to 60-second history. It does not remove the compatibility limitation.

### Evening 4F check

Caller lookup trained on afternoon data answers 193/268 evening opportunities; 60-second history answers 263/268. Every answered case is labeled 4F correctly. Only one true class is present and a constant 4F prediction scores 100%, so this supports conditional stability and coverage rather than general floor accuracy.

## 5. Sensitivities and confounds

- **Reference policy:** caller E2 → E1 retains 92 opportunities under tighter rules and 128 under looser rules. Lookup → 60-second-history coverage is 33.7% → 44.6% under tight rules and 40.6% → 50.0% under loose rules. The coverage finding remains.
- **Recovered CNI:** two extra raw-only results give 128 E1 labeled opportunities. Lookup/history overall accuracy becomes 38.3%/46.9%; interpretation is unchanged. This is an anchored-result sensitivity, not corrected probe yield.
- **Periodic local observations:** 10/30/60-second sampling uses four start phases. A ≤5-second radio-age gate loses many readings; some 60-second phases have no usable predictions. Accepting readings up to 30 seconds old increases coverage under a different freshness assumption.
- **Example:** E2 → E1 at 10-second sampling with ≤30-second radio age gives single-Cell overall accuracy 60.1–62.1% across phases and 60-second-history accuracy 65.5–66.2%. These phases share one recording and are not independent replications.
- **Clock sensitivity:** predefined ±1/±5-second target-query shifts change Cell agreement and some known/unknown reference decisions. No accepted reference changes directly to another named floor. No shift was optimized, and this does not establish synchronization.
- **Coarse vertical task:** the additional B1-versus-upper-floor diagnostic gives strong conditional Cell results, but time-only control already achieves 87.9–93.5% balanced accuracy at full coverage. The repeated itinerary is a major confound. This diagnostic does not replace the five-floor task.

## 6. Horizontal precision

Use qualified non-mock fused fixes with effective age ≤5 seconds and reported horizontal accuracy ≤30 m. Training coordinates are deduplicated and separate from test fixes. A constant baseline uses the training-coordinate median; Cell estimates use training coordinates only.

Median discrepancy on the **same rows**:

| Train → test | Database | Matched rows | Single-Cell estimate | Constant training position |
|---|---|---:|---:|---:|
| E1 → E2 | Local fresh updates | 54 | 75.6 m | 29.0 m |
| E2 → E1 | Local fresh updates | 52 | 73.6 m | 58.7 m |
| E1 → E2 | Caller | 15 | 91.1 m | 52.5 m |
| E2 → E1 | Caller | 34 | 91.6 m | 70.7 m |

Cell/history methods do not consistently beat the constant baseline on excursion holdouts. Evening local → caller transfer does improve median discrepancy (33.0 versus 96.9 m on 125 rows), but the qualified test coordinates occupy a tiny cluster. These are discrepancies to noisy fused fixes, not measured physical indoor positioning error.

No new geolocation-service requests were made. Improving on a broad service radius alone would not establish added Cell information; a constant known-building position is an essential control.

## 7. What to do next

1. **Keep both databases.** There is Cell/floor structure worth investigating; preserve the local/caller distinction and actual probe availability.
2. **Balance missing evidence.** Collect longer known-floor segments on 1F/2F/3F, including multiple horizontal positions. Current second-excursion caller support is only one/two/one Cell observations there.
3. **Change order and duration on the next visit.** Different floor order and stay lengths help separate Cell information from the repeated itinerary. Repeat on another date before claiming repeatability.
4. **Keep manual work small.** Select the floor once at each segment start; the segment shares that label. Altitude reconstruction can assist with transitions. No per-sample typing or video is required.
5. **Use a small paired collection check.** Verify several distinct modem updates and successful caller results per intended floor. One-second row counts alone are misleading.

Building recognition needs other identified buildings/areas. Precise horizontal and transition-landmark claims need better position/route references. This batch supplies neither test, so neither is scored as a success.

## Artifacts and verification

Private inputs/outputs are under captures/batch_20261112/analysis/. The compact entry point is RESULTS_SUMMARY.json. Full metrics, predictions, intervals and profiles remain available.

- 98 split/stream evaluations each for five-floor, coarse-vertical and horizontal analyses. These are correlated protocol/sensitivity comparisons, not 98 independent experiments.
- All 25 original copied files retain their recorded sizes and hashes.
- Independent review checked aliases, all 435 primary native CNI timestamps/tuples, reference guards, causal radio acquisition and all split overlaps. No cached measurement/fix IDs overlap training and test.
- Focused preparation/floor/horizontal checks passed: five, ten and six tests respectively.
- Original recordings remain unchanged. No phone operations, app modifications, Android builds or paid API calls were performed during analysis.

Reproduction commands are in [analysis/README.md](../analysis/README.md). See [the analysis plan](BATCH_ANALYSIS_PLAN.md) for the intended comparisons and controls.
