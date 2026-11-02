# Research discovery goal — agreed brief

## Current focus — 2026-10-28

The user has made **a surveyed mall Cell database and improved location precision** the primary direction. Start with local LocationData collection, compare single-cell lookup with ID distributions/sequences offline, then validate the improvement using caller probes. Region/building identification, floors and transition landmarks remain the main capabilities. **Departure detection and fewer probes are backup directions.** See [LOCAL_DATABASE_PRECISION_PLAN.md](LOCAL_DATABASE_PRECISION_PLAN.md) for the current scope and input/validation boundaries. Initial local collection may omit probes; a small paired check precedes large-scale expansion and independent caller validation. Target-only signal, neighbor, Wi-Fi and altitude features do not enter the primary caller-compatible model. Successful CNI remains current/latest under the user's confirmed premise.

## Previous follow-up — 2026-10-15

The user has selected **building/region identification, floor/vertical-region identification, cell-transition landmarks, and region departure with fewer probes** as the four focus directions. The current task is a literature/method review, additional testable ideas, and a concrete experiment/data-collection design, rather than selecting only one direction. See [FOUR_DIRECTION_LITERATURE_REVIEW.md](FOUR_DIRECTION_LITERATURE_REVIEW.md) and [FOUR_DIRECTION_EXPERIMENT_PLAN.md](FOUR_DIRECTION_EXPERIMENT_PLAN.md). Successful CNI observations are treated as current/latest under the user's confirmed premise. This update does not initiate field collection or app changes; the discovery record below is historical.

## Original discovery record — 2026-10-06

Approved for implementation by the user on 2026-10-06. Status: **discovery complete — recommendation revised after the user's challenge**. Results and completion checks are recorded in [RESEARCH_OPPORTUNITY_REVIEW.md](RESEARCH_OPPORTUNITY_REVIEW.md).

**No preferred capability direction currently clears the contribution and practical-feasibility bar.** The known-area departure proposal is withdrawn as the leading direction: it does not establish inference at unseen places, and the user reports that the present target-Wi-Fi-dependent channel stops supplying Cell ID when Wi-Fi is lost. Screen-state timing remains an unverified hypothesis rather than an automatically promoted recommendation. A small observation-channel check could establish prerequisites for further discovery; it is not itself a new inference contribution. Further field collection and application changes follow discussion.

**Later application clarification (2026-10-06):** the user explicitly includes a known mall/building with prior information. Venue calibration is acceptable for that bounded use case. [APPLICATION_RESEARCH_DIRECTIONS.md](APPLICATION_RESEARCH_DIRECTIONS.md) reviews building/floor capability screens, budgeted departure, and further application questions under this assumption. It proposes a screening order rather than a newly qualified preferred contribution; unfamiliar-building transfer and Wi-Fi-loss limits still require separate evidence.

**Earlier follow-up phase:** expanded the testable application-led [candidate pool](RESEARCH_CANDIDATE_POOL.md), before the four focus directions were selected on 2026-10-15. No new field collection was initiated by that phase.

## Objective

Identify one worthwhile research direction that reveals additional information through **caller-visible IMS/VoWiFi signaling**, or materially improves an existing inference. It must have a defensible unresolved contribution and plausible practical value. A finished application is unnecessary.

Recommend one direction and at most two useful alternatives. If no candidate qualifies, report that finding and discuss scope expansion with the user. A search without a matching paper does not establish novelty.

## Settled scope and constraints

| Decision | Agreed choice |
|---|---|
| Scope | Current CNI mechanism and adjacent IMS/VoWiFi signaling, metadata, timing, and state-dependent inference. Broader telecommunications research requires later discussion. |
| Preferred contribution | Advance inference capability. Mechanism, provenance, privacy, and mitigation findings can support the evaluation. |
| Observer | Predictions use information available to the caller. Target measurements are experimental validation and controls. |
| Resources | Existing authorized devices, carrier setup, hotspot/access networks, capture tooling, and local data first. Additional equipment is conditional on evidence. |
| Initial effort | A small, decisive pilot before committing to a larger study. |
| Practical transfer | A known-site/day split does not establish use at unseen places. Declare any required site/target-specific survey and evaluate whether that restriction leaves worthwhile value. |
| Observation availability | Evaluate ordinary loss/recovery of target Wi-Fi, not only routes engineered to maintain access. Missing Cell ID cannot by itself distinguish departure from a stationary access failure. |
| Discovery endpoint | Reviewed recommendation, including pilot design. New field experiments and implementation follow the recommendation discussion. |
| Additional evidence | Inspect `/home/ian/AndroidStudioProjects/LocationData`, alongside `/home/ian/AndroidStudioProjects/CellIDTracker`. |

The original shorthand `/AndroidProjects/LocationData` resolves to the existing sibling project above. This phase reads that project without changing it.

Research uses owned or explicitly authorized devices and networks. Raw identifiers, coordinates, SIP captures, credentials, and authentication material remain private. Literature searches use generic research terms; local identifiers are not sent to geolocation services.

## Establish the available evidence

1. Inventory caller exports, paired target exports, captures, schema/build versions, campaign and condition mappings, time overlap, and interrupted or incomplete recordings. The starting paired target inventory is nine sessions and 2,185 samples; verify the larger capture tree rather than treating this as nine independent movement trials.
2. Distinguish implemented fields from saved, nonempty, informative measurements. Include registered and neighboring cells, signal metrics, Timing Advance where populated, Wi-Fi, power state, position, and speed.
3. Audit radio and location freshness separately, actual sampling cadence, repeated cached measurements, registered-cell status, and cross-phone clock alignment. A recent callback does not make every field recent.
4. Treat speed classes as movement proxies. Check manual labels and Activity Recognition availability explicitly.
5. Preserve earlier negative results and their limits. State which questions can reuse current recordings and which require new collection.

## Research and challenge candidates

1. Search issuing standards bodies and original studies on caller-visible IMS/VoWiFi metadata, signaling inference, timing, state-dependent observations, and limits of cell-based inference.
2. Record supporting and contrary evidence, publication dates/versions, exact search terms, retrieval date, and each study's observer, measurements, training assumptions, and access requirements.
3. Generate up to twelve distinct questions, each naming the extra information or improved capability it could provide.
4. Investigate the strongest three deeply. Read their closest prior work, follow relevant references, and search specifically for existing answers, counterexamples, confounds, and observability limits. A finalist may be rejected after this investigation.
5. Have an independent reviewer challenge the shortlist. Resolve objections using verified sources and actual measurements; preserve unresolved uncertainty rather than hiding it.

## Qualification gates

| Gate | Required evidence |
|---|---|
| Practical/research value | Explain the useful inference and the contribution of either outcome. An algorithm name or speculative application is insufficient. |
| Prior work | State what comparable work already answers and the exact remaining difference. Different access assumptions must be made explicit. |
| Counterevidence | Search for disproof and include relevant local negative results and protocol limits. |
| Measurement feasibility | Separate caller predictors from target truth and controls. Identify missing measurements and any observer interference. |
| Pilot design | Specify controls, independent replication, measurable outcomes, decision thresholds, competing explanations, and an abandonment or revision result. |

Passing these gates justifies a pilot, not a claim that the proposed inference already works. A small pilot cannot establish deployment reliability or novelty by itself.

## Required deliverables

- Update this agreed goal document.
- Produce `docs/RESEARCH_OPPORTUNITY_REVIEW.md` containing the audited inventory, literature/access map, candidate comparison, rejection reasons, verified references, and search log.
- For each of three investigated finalists: exact question, practical benefit, closest prior work, unresolved contribution, supporting and contrary evidence, required measurements, competing explanations, uncertainty, pilot controls and outcomes, replication needs, and stop/revision criteria.
- Recommend one direction with at most two useful alternatives, or explain why none qualifies.

## Completion checks

- [x] Both projects and existing captures inspected; denominators and incomplete sessions distinguished.
- [x] Field population, registered status, freshness, cadence, clocks, labels, and observer effects audited.
- [x] Previous negative findings retained with appropriate scope.
- [x] Bounded candidate set compared; three finalists investigated against original sources and counterevidence.
- [x] Independent objections recorded and resolved or explicitly retained.
- [x] Reassessed qualification after the user's challenge: A withdrawn as preferred; B unverified; no preferred capability direction currently qualifies. A feasible measurement procedure alone does not pass the contribution gate.
- [x] Review and references checked; only sanitized aggregate evidence included.
- [x] Discovery ends at the reviewed finding of no currently qualifying preferred direction; further collection or scope expansion awaits discussion.
