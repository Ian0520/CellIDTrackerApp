# Application-led caller inference opportunities

Reviewed **2026-10-06 (Asia/Taipei)**. Follow-up to [the discovery review](RESEARCH_OPPORTUNITY_REVIEW.md) and [the Wi-Fi-loss review](WIFI_LOSS_OBSERVABILITY_REVIEW.md), incorporating the user's building/floor and reduced-probing proposals. Literature, a narrowly scoped local capture check, and independent review only; no new phone experiments or application changes.

**Latest user steering:** continue accumulating plausible, application-led testable directions and combine experiments later. [RESEARCH_CANDIDATE_POOL.md](RESEARCH_CANDIDATE_POOL.md) expands the bank under this instruction. The screening order below is retained as an earlier proposal, not an instruction to start collection or select a winner now.

## 1. Revised question and scope

**Can a known venue's indoor cellular deployment leave a building or vertical-zone distinction in caller-visible CNI, and under what conditions is that distinction useful?** This is the most direct capability to screen with the current equipment. It is a conditional research question, not a demonstrated capability or a newly qualified research contribution.

The user now explicitly allows a known mall with prior information. A venue survey is therefore a legitimate application condition. A classifier for that venue does not establish inference at arbitrary unfamiliar buildings. Useful outputs can be building membership, a predefined floor group, or an event; they need not be a more precise latitude/longitude estimate. Correct building membership is not meter-level indoor positioning.

Predictions use caller observations. LocationData's target cells, RSSI, neighboring cells, Wi-Fi, position, and manual labels can select a promising site and validate results. They do not become live inputs to a caller-only predictor. Venue AP/controller telemetry would be a different observation architecture and a useful application baseline if available.

The old known-area departure recommendation remains withdrawn as a preferred contribution. This follow-up changes the application assumptions and proposes a new capability screen; it does not erase that finding or revive unreplicated timing effects.

## 2. What the evidence permits

### Positive floor evidence, and the deployment counterexample

An original **GLOBECOM Workshops 2015** experiment placed four known LTE femtocells across two floors. Its Cell-ID estimator used the acquired transmitter identity and mapped height without adding RSS/ranging estimates; reported floor correctness was 69.88%. Local radio acquisition in this controlled topology differs from receiving the target's most-recently-camped CNI through IMS. It establishes a conditional possibility, not commercial mall reliability. [Original paper, §IV and Table I](https://spcomnav.uab.cat/docs/conferences/DelPeral_GLOBECOM_2015.pdf).

Conversely, Ericsson describes combining multiple indoor Radio Dot units into a single cell. Several ceiling radios or floors therefore need not expose different identities. Whether a particular venue has distinct useful cells must be measured. [First-party deployment description, 16 June 2025](https://www.ericsson.com/en/news/2025/6/ericsson-named-top-small-cells-vendor-by-abi-research).

### Richer cellular studies do not establish our capability

**Otsason et al. (UbiComp 2005)** use wide GSM signal-strength fingerprints; even their one-cell comparison includes signal strength. **Górak et al. (2016)** also use multiple-cell signal strengths. Those are unavailable through the current CNI channel. [Original 2005 paper](https://www.cs.toronto.edu/~delara/papers/ubicomp2005/ubicomp.pdf), [original 2016 article](https://onlinelibrary.wiley.com/doi/10.1155/2016/2719576).

**UniCellular (2023)** and its expanded **2026 TSAS** article describe serving-tower RSS sequences. Their single-tower premise is richer than a single ID. Original full texts were inaccessible in this review; the original indexed abstract and author publication metadata were checked. Do not import their reported accuracy or experimental assumptions. [2023 DOI](https://doi.org/10.1145/3589132.3625635), [2026 DOI](https://doi.org/10.1145/3708986), [author publication record](https://sherifmost.github.io/).

### Direct packet fields: defined is different from observed

CNI describes the most recently camped cellular cell; its identity has no standardized building/floor label. PANI can contain a WLAN AP BSSID, potentially useful with a venue AP inventory, but forwarding is constrained and this field has not appeared in our audited response population. [TS 24.229 V19.6.0, §§7.2.15 and 7.2A.4.3](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.06.00_60/ts_124229v190600p.pdf), [RFC 7315](https://www.rfc-editor.org/rfc/rfc7315.html), [RFC 9878](https://www.rfc-editor.org/rfc/rfc9878.html).

PIDF-LO civic objects can express building (`BLD`) and floor (`FLR`). This does not show that ordinary callee progress responses disclose them: RFC 6442's response-location use concerns correction of the originally supplied location, not automatic callee-location delivery. [RFC 5139, §3](https://www.rfc-editor.org/rfc/rfc5139.html), [RFC 6442, §§4–5](https://www.rfc-editor.org/rfc/rfc6442.html).

**New scoped local check:** across the existing 12 raw/native streams, the 1,152 recorded incoming INVITE-response copies contained zero `Geolocation`, `Geolocation-Error`, `Geolocation-Routing`, or PANI headers. No PIDF MIME/namespace markers were found in their captured payloads; 480 copies had `application/sdp` content type. These are packet-copy counts including repeats, not independent probes. Header/marker checks do not guarantee complete TCP reassembly or semantic XML validation. The result does not cover all SIP message types, future conditions, or other networks. No raw identifiers, coordinates, header values, or payloads were exported.

Thus the current indoor hypothesis is **spatial information in identities/history**, rather than an already available height field. An AP-ID branch remains conditional on actual target-origin visibility, with cross-floor AP coverage as a confound.

## 3. Refined capability questions

| Candidate | Useful decision | Required distinguishing evidence | Critical failure or scope limit |
|---|---|---|---|
| **A. Known-building membership** | This venue versus its surrounding neighborhood; building-level service/check-in | Indoor identities or histories distinguish interior from entrance, sidewalk, and adjacent building | Same footprint outside, stale outdoor CNI indoors, or apparent success solely from Wi-Fi availability |
| **B. Floor or vertical-zone inference** | Which predefined floor/group, or whether it changed | Mapped indoor cells or repeatable histories distinguish vertical classes across multiple horizontal positions | Shared-cell deployment; same-floor reselection; memorizing one lobby/elevator rather than floor |
| **C. Departure with fewer probes** | Reduce queries while keeping a useful alert delay | A validated physical-boundary event, then an explicit error/delay/unknown/probe-count tradeoff | No observable outside evidence; stationary Wi-Fi loss mimics exit; fewer probes merely hide departures |

### A. Building membership first

Ask whether caller CNI preserves an indoor cellular footprint specific enough to support a building decision. A small indoor cell may provide a much smaller semantic area than the surrounding macro-cell estimate; a macro-cell shared by the entire neighborhood may not. Do not identify a building by the nearest tower coordinate.

A one-sided high-confidence inside result can still be useful, but report its coverage and expire it when no current evidence supports it. Outside observations without ordinary target Wi-Fi are **unknown**, not confirmed outside. Target topology surveys outside are not proof of remote observability there.

The unresolved contribution would be a reproducible remote-channel capability, its freshness and spillover limits, and an eligibility/calibration procedure across venue types. One known-cell lookup at one mall alone is weak research. For advertising, compare the resulting precision, coverage, latency and deployment requirements with the venue's own Wi-Fi telemetry where available; do not claim a commercial advantage without evidence.

### B. Floor as a conditional next milestone

Start with exact floors or spatially defined groups selected before confirmatory evaluation. Basement versus sales floors may be useful if exact floor numbers are indistinguishable. Groups discovered in exploratory data need a frozen definition and held-out confirmation.

A floor-transition detector is another hypothesis, not automatically easier: control same-floor walking, stationary cell changes, elevator AP changes and access gaps. Up/down direction requires an ordered spatial mapping; arbitrary ID numeric order supplies none. Short histories may add information when one ID is ambiguous, but cannot distinguish physically different situations whose observable histories are identical.

Keep full globally qualified identities. Compare the existing `ECI >> 8` grouping separately: it suppressed some stationary variation, but may erase indoor distinctions. Neither the prefix nor suffix is a verified floor or physical-tower label.

### C. Reduced probing follows event observability

Refine the objective to: **minimize actual query count subject to useful exit detection, limited false alerts, and an explicit maximum blind interval**. Without a push event, completely stopping probes prevents new departure evidence until another observation. A leave-and-return between checks may be missed.

The polling gap bounds the next opportunity only if the event remains immediately observable; stale CNI, shared cells and Wi-Fi loss can defeat a physical-exit deadline. Measure actual decision time, not a retrospectively assigned boundary timestamp. Savings in queries are not automatically battery savings.

**PlaceSenseGSM (2009)** is close prior work: one currently connected cell ID every ten seconds, place boundary inference, and coarse sensing to trigger finer Wi-Fi sensing. It reports nearby-place merging and false splitting during stationary handoffs. Its target-side current-cell access differs from remote CNI. [Original paper](https://www.cs.ucf.edu/~lboloni/Teaching/EEL6788_2010/papers/Kim-PlacesRFBeacons.pdf).

Adaptive pull sensing also has established theory, including 2026 preprints for full and partial Markov-state observations. Neither proves geographic membership from historical CNI; adding AI or a variable interval alone is insufficient contribution. [Zanni et al., 13 August 2026](https://arxiv.org/html/2608.13248v1), [Cosandal et al., 29 June 2026](https://arxiv.org/html/2606.30623v1).

## 4. Further problem-driven directions

| Direction and exact question | Application | Initial falsification check and limits |
|---|---|---|
| **Entry/gateway or journey phase:** can histories distinguish entering, passing outside, and dwelling when the latest ID is ambiguous? | Arrival/check-in or entrance-specific service without meter coordinates | Two entry paths plus matched bypass/internal paths; latest-ID and time-only baselines. Site graph/survey and observable access needed; do not classify Wi-Fi association as physical entry. |
| **Repeat visit and stay intervals:** can we recognize return to a distinguishable venue and bound arrival/departure times? | Visit summaries, return-triggered service, avoiding duplicate events | Repeated visits plus neighboring-place negatives. Two inside observations do not prove uninterrupted stay; brief exit/return can be missed. PlaceSense is direct prior art. |
| **Relative co-movement/separation:** can two authorized targets' aligned histories reveal a shared coarse journey and later split without mapping every ID to an address? | Group coordination or coarse asset journeys | Together/split/parallel/unrelated-path controls and held-out route. Requires another target phone and ordinary access for both; one shared cell is not proximity, and different cells are not separation. |
| **Unfamiliar-site context-change trigger:** after a short starting observation, can an identity-independent rule detect a useful change of radio context? | Request a closer check or retire an old context-based decision | Hold out sites and IDs; include stationary reselection and access interruption. Output is a change trigger, not proof of a geographic boundary crossing. |

Co-movement is the most distinct additional information type here, but has substantial prior art. **cellSim (2019 preprint)** matches cellular trajectories using operator tower coordinates and roads, and explicitly observes that co-moving same-carrier phones can use different cells. A caller-only, label-light comparison remains an unverified hypothesis; it does not establish close physical contact. [Original preprint, v2](https://arxiv.org/pdf/1907.12371).

Known-route sequence inference also has precedent: **CAPS (2011)** uses phone-side cell history and GPS-trained route information. Remote, sparse, Wi-Fi-dependent observability requires separate validation. [Original paper, previously read in discovery](https://cseweb.ucsd.edu/~schulman/class/cse291_w17/docs/paek_caps.pdf).

Freshness and useful-observation/retry scheduling support all these directions. They become a separate capability contribution only if they expose useful new history or materially improve decisions. An unvalidated `cell-info-age=0` cannot certify current physical position.

## 5. Small proposed screening pilot

This is a design for discussion, not executed collection. Existing recordings have no manually labelled building/floor/exit trials; stationary data cannot answer it.

1. **Select one accessible known venue.** Use fresh explicitly registered target-cell readings or a known deployment map to find a promising topology. Distinct target cells are an eligibility clue, not caller proof. Count distinct modem measurements, not repeated LocationData callbacks.
2. **Predefine spatial labels.** Two or three separated floors, several interior/window positions on each, entrance, ordinary-access exterior sidewalk and adjacent-building negatives where accessible. Use manually verified positions/floor labels, not GPS altitude as floor truth.
3. **Include controls.** Stationary stays, same-floor walking, inter-floor transitions, entry/exit/return, and stationary Wi-Fi interruption. Keep ordinary venue connectivity and the existing probe policy; do not use a moving hotspot to claim normal exit coverage.
4. **Confirm the reporting path.** Match real CNI-bearing packet times with fresh registered target readings, bound cross-phone clock uncertainty, and separate malformed/missing/censored responses from stale information. The app's first-progress time may not be the CNI packet time; selected-response spacing is not INVITE send cadence.
5. **Explore then freeze.** Compare building-only, latest full-ID, prefix, and short-history rules. If groups are explored, freeze them before a second-day whole-visit test and test new horizontal positions on the same floors. Adjacent repeated samples are not independent train/test data.
6. **Report decisions and availability together.** Building/floor precision, class confusion, response/output coverage, unknown fraction and durations, false inside/outside alerts, post-transition stale claims, true online delay, actual attempts and incomplete episodes. A small screen cannot establish commercial reliability.
7. **Stop or narrow based on information.** If only a venue distinction survives, retain building membership and drop exact floor. If remote CNI fails to reflect indoor transitions, or all candidate classes share indistinguishable histories, park indoor inference at that site. If exits always lose all usable evidence, reject timely physical-exit claims there. Do not optimize a scheduler before a usable event exists.

Only after this screen would further venues/handsets/carrier settings, calibration-cost evaluation, or prospective adaptive polling be justified. Thinning old traces is exploratory; it does not prove what different live probing would have caused.

## 6. Independent challenge and remaining uncertainty

Separate indoor-literature, adaptive-sensing, and creative-review analyses challenged the synthesis. Incorporated corrections: known-site calibration is allowed for this use case; shared indoor cells are a real counterexample; all missing exterior CNI stays unknown; held-out horizontal positions prevent lobby memorization; explicit floors/groups precede confirmatory scoring; repeat visits are not continuous-dwell proof; and advertising needs an application comparison, not only classifier accuracy.

**Recommendation:** screen known-building membership and conditional floor/vertical-zone information first. Treat budgeted departure as a dependent follow-up and co-movement as a resource-dependent alternative. None is claimed novel or operational yet. A useful result must survive freshness, nearby negatives, availability and whole-visit replication checks, then a contribution review against the listed prior work.

## 7. Search and access record

Search/retrieval date: **2026-10-06**. Primary studies, issuing standards and first-party deployment material are linked at the relevant claims. Original full texts read for GLOBECOM 2015, Otsason 2005, Górak 2016, PlaceSense 2009, cellSim 2019, ETSI/IETF standards, and the pull-sensing preprints. UniCellular full texts remained unavailable; abstract/author metadata only. CAPS was read in the earlier discovery phase, not newly retrieved successfully here. Absence of a matching paper is not novelty evidence.

Representative exact queries from the parallel reviews:

- `"indoor" "floor" "cell id" cellular localization study`
- `"distributed antenna system" "same cell" floors indoor positioning`
- `"Floor Detection with Indoor Vertical Positioning" "Cell-ID"`
- `"Accurate GSM Indoor Localization" "pdf" Otsason`
- `"UniCellular" "floor" RSS pdf`
- `"Ubiquitous and Low-Overhead Floor Identification with Limited Cellular Information"`
- `site.etsi.org "i-wlan-node-id" "MAC address" "24.229"`
- `site.rfc-editor.org "FLR" "floor" "5139"`
- `site.rfc-editor.org "6442" "response" "Geolocation"`
- `adaptive sampling geofencing cellular cell id departure event detection original paper`
- `PlaceSense PlaceMap cell ID arrival departure unknown places original paper`
- `Age of incorrect information sampling remote monitoring Markov source event detection original paper`
- `"Which sensor to observe" "Timely tracking" Cosandal`
- `"co-mobility" "cell" "sequence"`
- `"Retrieving Similar Trajectories from Cellular Data at City Scale" pdf`
- `"cell-id" "sequence" "route" "CAPS" MobiSys`

The earlier reviews retain the complete original discovery inventory, rejected directions and Wi-Fi-loss counterevidence. This scoped application review does not repeat their searches or transform missing access into a positive inference result.
