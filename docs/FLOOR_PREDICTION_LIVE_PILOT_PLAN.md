# Floor research phases

Updated November 17, 2026. **Current phase: build the full caller dataset and replay the implemented
history tracker against those same recordings.** No train/test split is assigned;
independent validation and live testing remain deferred.
See [the current dataset analysis](FLOOR_DATABASE_ALGORITHM_REVIEW.md).

## 1. Current work

- Combine all accepted caller recordings, including the latest visit, into one
  caller presence relation. Local radio cells are outside the active analysis.
- Store each (complete Cell identity, floor) pair once. Repetition, floor stay
  duration and section stay duration do not weight an existing association.
- Inspect each cell's observed floors, which cells are only observed on one
  floor, overlap between floors and the recordings supporting each pair.
- Retain original chronology, reference provenance, uncertain intervals and
  failed observations for quality checks.
- Add further collection to the same databases as available. Prioritize broader
  horizontal coverage and repeated visits where evidence is sparse; exact equal
  durations are unnecessary.

The caller-history tracker is implemented offline. Within a fixed 60-second
window it prefers fewer floor changes, permits arbitrary elevator jumps and
outputs a single floor, adjacent pair or unknown. Its requested same-dataset
replay is documented in [the results](CALLER_FLOOR_TRACKER_REPLAY.md). The frozen
relation uses all accepted labels; runtime history uses no target floor labels.
No live Android prediction interface has been implemented.

## 2. Collect with little manual work

For a separate same-floor session, select building/floor once before Start.
In a continuous recording, use Start floor change, select the arrival floor,
then End floor change; changing selection alone is not a saved checkpoint.
Retain transition/uncertain intervals as unknown. No per-sample typing is needed.

Use actual caller CNI for the active Cell/floor database. LocationData can
continue to provide floor references and timing context. Keep target radio
measurements available as supporting records; they cannot supply caller Cell
IDs, fill missing probes or influence the proposed history tracker.

## 3. Later phases, when requested

After understanding the combined associations, decide what inference extension
is worth developing. Any future validation should use data distinct from the
relation being evaluated and distinguish exact-floor answers, candidate sets,
unknowns and Cell-return availability. No such split is part of the current workflow. The present scores deliberately
use the construction recordings and are marked as same-dataset results.

A live caller interface would eventually import a frozen relation, look up the
current returned Cell ID and log candidate outputs. If no Cell ID is returned, do not make a floor prediction. This interface and a new field accuracy experiment are not current
implementation requirements.
