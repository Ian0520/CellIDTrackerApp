# Caller-side IMS/VoWiFi research opportunity review

Research and local audit: **2026-10-06 (Asia/Taipei)**. Scope and completion contract: [RESEARCH_DISCOVERY_GOAL.md](RESEARCH_DISCOVERY_GOAL.md). Recommendation revised after the user's challenge concerning unseen places and loss of target Wi-Fi. This is a discovery review with retained pilot proposals, not a new experimental result.

## 1. Decision

**Withdraw area-departure inference as the preferred direction. No preferred capability direction currently clears the agreed contribution and practical-feasibility bar.**

The proposed inside/outside lookup/history rule requires a surveyed area and labelled caller observations. Testing that area on a second day evaluates repeatability at a familiar site, not inference at unseen places. An unfamiliar Cell ID must remain unknown; consecutive repetitions or an AI model cannot supply its missing geographic relationship. Known-site monitoring can have limited value, but that restriction and the established cell-history prior art do not presently support the claimed leading research contribution.

The user additionally reports that this deployed observation path requires target Wi-Fi and loses Cell ID when the target leaves Wi-Fi coverage. The stationary exports did not reproduce that transition, so retain it as a user-reported operational constraint requiring verification, not an audited experimental result. A stationary Wi-Fi disconnect can produce the same missingness as a departure. Keeping Wi-Fi available throughout a route, especially with a travelling hotspot, tests a restricted access condition and does not establish ordinary AP-departure detection.

**Pre-probe screen-state timing remains an unverified hypothesis.** It is not promoted automatically after A's rejection: all existing target samples are interactive, target involvement in the first 183 is unverified, and earlier timing comparisons were negative. Calibrated spatial regions remain a diagnostic idea with the same survey/transfer limits. The next useful prerequisite would be checking where caller observation survives, disappears and resumes across ordinary access changes (§7.1). That check is not itself a novel inference direction.

| Direction | Research value | Current support | Main uncertainty | Decision |
|---|---|---|---|---|
| A. Area departure with explicit unknown outcomes | Limited known-site diagnostic; broader contribution not established | Caller CNI works while observable; stationary controls expose raw-cell false alerts | Unseen places lack labels; unchanged coverage and Wi-Fi loss can erase the needed evidence | **Withdraw as preferred** |
| B. Screen on/off before a probe | Potential distinct target-state observation | Different timing channels have precedent; caller timing is recorded | No existing state contrast, verified target-origin timing or transferable effect | **Unverified hypothesis; not promoted** |
| C. Calibrated location region from CNI history | Diagnostic of spatial uncertainty | Existing point estimates have failed containment calibration | Survey dependence, spatial ambiguity and close prior work | **Defer standalone work** |

There is **no validated departure detector, screen classifier, or improved precision claim** in the present evidence. Detailed pilots below are retained to make the previous reasoning reviewable; their presence does not imply current qualification.

## 2. Evidence boundary and inventory

### 2.1 Projects and files inspected

- CellIDTracker: caller export/persistence, SIP capture and timing analysis, cell sequence analysis, experiment configuration, existing reports, and the private `captures/` tree.
- LocationData: `/home/ian/AndroidStudioProjects/LocationData/README.md`, `GroundTruthViewModel.kt`, `TelemetrySnapshot.kt`, `MainActivity.kt`, foreground service, and session persistence. Implementation/export schema is **6**; a schema-5 description remaining in its README is not authoritative for these exports.
- LocationData also contains a standalone `probe.pcap` and `probe-app.log`. No additional paired exported sessions were located there outside generated build/cache material. Their participant/condition/session pairing is not established; they are diagnostic leads, not extra scored target evidence. Authentication traffic and content were not analyzed.

The following counts come from local JSON/JSONL inspection and manifest validation. They describe saved records, not independent subjects or fresh radio measurements.

| Inventory item | Verified amount | Interpretation |
|---|---:|---|
| Capture-tree files | 205 | Includes raw/sanitized copies, archives, analysis outputs and figures; do not count these as independent captures |
| Caller exports | 13 schema-4 sessions; **338 persisted attempts** | Four unpaired diagnostics, nine paired sessions |
| Target exports | 9 schema-6 sessions; **2,185 samples** | One recorded target device/app profile and one registered target PLMN profile |
| Caller metadata/manifests/context streams | 13 of each | Raw and sanitized context are copies of the same observations |
| Native SIP streams | 12 raw + 12 sanitized | One zero-attempt paired session has no native stream |
| Attempts with a local target pair | **305** | Includes diagnostics; not the primary-analysis denominator |
| Existing seven-session stationary cell control | **276 attempts**, **212 cell observations** | Six A/B/A blocks plus one unchanged-A control |
| Existing SIP primary transactions | **209** | Six blocks of 30 plus unchanged-A control of 29 after eligibility filters |
| Build identities | 4 caller APK / 3 native across all files | Diagnostics span versions; the seven primary/control sessions use **one APK and one native build** |
| Primary/control settings | 10 s minimum, 1 provisional, auto-restart enabled | A diagnostic configuration records 5 s; this does not justify faster prospective collection |
| Manifest integrity | 0 checked hash/size failures, copy/context errors, or recorded native drops | Missing final summaries in interrupted sessions cannot certify zero unreported loss |

### 2.2 Pairing and incomplete recordings

Chronological aliases below are report-only labels. Private session IDs and timestamps are omitted. A/B denote the established university Wi-Fi/hotspot conditions; reuse the existing analysis mappings rather than inferring conditions from a filename.

| Alias | Role | Caller attempts | Target samples | Attempts inside target time window | Nearest target callback within 1 s / 5 s |
|---|---|---:|---:|---:|---|
| S01 | Unpaired diagnostic; no final native summary | 15 | — | — | — |
| S02 | Unpaired diagnostic; multiple process starts | 5 | — | — | — |
| S03 | Unpaired diagnostic; no final native summary | 3 | — | — | — |
| S04 | Unpaired diagnostic | 10 | — | — | — |
| S05 | Day 1 A1 | 52 | 257 | 52/52 | 35/52; 52/52 |
| S06 | Day 1 B | 31 | 56 | 31/31 | 6/31; 31/31 |
| S07 | Paired diagnostic, outside primary manifests | 29 | 50 | 29/29 | 8/29; 29/29 |
| S08 | Day 1 A2 | 52 | 90 | 52/52 | 15/52; 52/52 |
| S09 | Day 2 A1 | 34 | 402 | 34/34 | 34/34; 34/34 |
| S10 | Day 2 unchanged-A control | 31 | 416 | 31/31 | 31/31; 31/31 |
| S11 | Paired diagnostic; very short, zero attempts | 0 | 12 | 0/0 | 0/0; 0/0 |
| S12 | Day 2 corrected B | 37 | 437 | 37/37 | 37/37; 37/37 |
| S13 | Day 2 A2 | 39 | 465 | 39/39 | 39/39; 39/39 |

Campaign labels match within each pair. S12 needs the **existing declared condition-label correction**; literal condition strings differ. Day 1 A2 includes transitional frequency/transport samples excluded by SIP eligibility. Do not silently relabel source JSON or merge diagnostics into the original comparisons. Repeated probes and restarts do not add independent day/device/operator replication.

An export pitfall: `outcome=cell_observed` occurs 256 times, but only **248** records have a complete nonnegative numerical cell tuple. Eight oldest unpaired diagnostic records have placeholder identities and no provisional timestamp. Four corresponding raw provisional copies contain CNI, but the historical app-event omission is unresolved. Count valid parsed observations, not merely a non-null export field or outcome name.

### 2.3 Caller-visible fields and their packet sources

Counts below cover the **12 native streams**, including diagnostics. INVITE-response packet copies include retransmissions. The stored attempt and correlated first response remain the inference unit.

| Observation | Actual source | Saved availability | Legitimate use and limit |
|---|---|---|---|
| First-provisional delay (`deltaMs`) | Caller monotonic INVITE send to matching first non-100 provisional response | 326/338; all saved statuses are 183 | Aggregate signaling delay; not propagation distance or target CPU time |
| 100 Trying timing; 100-to-183 timing | Separate matching incoming 100 and 183, native monotonic capture times | Packet evidence and existing stage analyzer available; join per transaction | Nuisance comparison for initial path delay; subtraction does not remove every caller/network effect |
| Numerical cell tuple | CNI in incoming 183; sometimes repeated in final 487 for the INVITE | 248 valid caller export tuples; 223 paired attempts matched to a parseable first-response CNI | LTE PLMN/TAC/ECI observation; at most one structured observation per probe |
| CNI RAT and `cell-info-age` | Native incoming CNI header, not retained in schema-4 attempt export | 645 header copies: 391 in 183, 254 in 487; all parsed LTE and age 0 | Header age is a protocol report, not independently verified location freshness |
| P-Access-Network-Info | Incoming INVITE responses | **0 copies** | Defined by standards and exposed in prior work, but absent on this path |
| Contact | 181/183/487/500 INVITE responses | 821 copies; no observed `+sip.instance`, `gr`, or recognized capability parameters | Routing/contact metadata; no direct device identifier demonstrated here |
| Record-Route | 181/183 | 482 copies | IMS dialog route, not target location |
| Server/Supported/Allow | Response metadata | Server/Supported present in 181/183/487/500; Allow in 181/183 | May describe network infrastructure or negotiated behavior |
| SDP origin/connection/codecs | Body of 183 when recorded | Existing observability analysis reads them | Media/session description; possible relay or offer constraints, not proven handset/public Wi-Fi address |
| Transport source | Capture envelope for a response | Five distinct source addresses in these streams | Observed IMS-path sender; **not** the optional `Response-Source` header or confirmed target endpoint |
| Reason, History-Info, P-Early-Media, Response-Source | Incoming INVITE response headers | **0 copies each** | No deployed feature can be assumed from their standard definitions |
| Caller Wi-Fi/network/power/process context | Separate caller context stream | 3,697 records | Confound/quality controls; caller Wi-Fi RSSI is not target RSSI |
| Location API result | Caller application export | Estimated coordinates/radius null in all 338; 248 failures, 90 not requested | Current captures contain no successful online geolocation estimate |

Native INVITE-response copies: 100=331, 183=480, 487=338, 181=2, 500=1; **1,152 total**, 167 flagged repeats. Restricting to the old seven blocks reproduces 100=277, 183=399, 487=282, 181=2, 500=1. The larger tally is extra diagnostic scope, not a revision of the old result or 1,152 independent probes.

### 2.4 Target fields: implemented versus useful in saved data

Target measurements below support training surveys, labels, controls, and validation. They are **not inference inputs at deployment**.

| Field group | Actual saved content | Usability limit |
|---|---|---|
| Position/fix quality | 2,185 lat/lon/accuracy/provider/age/mock records; all provider `fused`, mock=false | Fused location is an estimate, not error-free surveyed truth |
| Speed and uncertainty | Speed 2,175; speed accuracy 2,179; bearing 1,974; bearing accuracy 1,978 | Speed classes are proxies; missing/noisy values cannot label departures |
| Movement proxy | Stationary 1,827; slow 328; medium 20; unknown 10; all automatic | These counts are **not verified movement trials** in stationary recordings |
| Registered serving cells | Exactly one explicitly registered cell per sample; all registered serving RATs LTE | Flat fields agree with registered status here; source fallback to first unregistered cell must be excluded in future data |
| Cell snapshots | 15,304 rows: 2,185 registered and 13,119 nonregistered; LTE 14,676, NR 508, GSM 120 | Neighbor PCI/channel/signal exist, but full cell ID and MCC/MNC are populated only in the 2,185 registered rows. Cannot match CNI ECI to a fully identified neighbor from these exports |
| Radio freshness | Timestamp/age in every cell row; **249 distinct registered identity+measurement-time pairs** | 2,185 callbacks largely repeat cached modem readings |
| RSRP/RSRQ/RSSI | RSRP and RSRQ 14,676 rows; RSSI 14,796; recorded variation exists | Mostly includes neighbor snapshots and repeated samples; no caller target-RSSI field |
| Timing Advance | 569 non-null cell-row values, only two distinct recorded values | Limited variation and freshness; not caller-visible ranging or triangulation |
| NR signal metrics | 508 NR cell rows have empty signal-metric maps | Implemented NR metric names do not establish usable NR measurements |
| Data/service state | dataState and dataActivity populated in all 2,185 samples; zero is a recorded state value, not missing data | networkType, serviceState, roaming, emergency and operator descriptions are null; registration arrays are empty in all samples |
| Wi-Fi | RSSI/frequency/link speed/AP hash populated 2,182; Wi-Fi transport 2,182, cellular 3 | Session-salted AP hashes cannot demonstrate equality across sessions; some transitional samples need exclusions |
| Power context | Interactive=true, powerSave=false, deviceIdle=false, charging=false in **all 2,185** | No screen, Doze or power-save contrast. All nine session configs enable `keepScreenOn` |
| Battery/network context | Battery and temperature vary; validated=true; transport/metered vary | Context variation alone is not causal evidence of a caller feature |
| Activity Recognition | Class, confidence and age all null | No Activity Recognition results to train or validate activity inference |
| Manual events | **0** across all nine sessions | Source supports labels, but none were collected; no precise departure/return truth |
| Cell transitions | 130 confirmed observations; all same-area cell/sector changes | App debounce over cached samples, not exact modem handover, TAU, RRC, paging or IMS events |

### 2.5 Timing, cadence, freshness and observer effects

| Check | Observed result | Consequence |
|---|---|---|
| Caller selected-response intervals | 314 recorded response-to-response intervals; median **11.261 s**, P90 12.826 s, maximum 42.924 s | These are not verified INVITE send intervals. The native 10 s setting is a within-process minimum; use actual sends and process runs to establish cadence |
| Target callbacks | Overall median 1.001 s; P90 5.699 s; maximum 9.041 s | Early sessions often have roughly 7.5–7.7 s medians; nominal 1 Hz is not actual cadence throughout |
| Position age | Median 45 ms; maximum 3.739 s | 2,185/2,185 fixes aged ≤5 s; filter fix age separately from radio age |
| Position reported accuracy | Median 10.7 m; P90 20.3 m; maximum 58.1 m | 1,960/2,185 ≤20 m; avoid scoring a narrow boundary inside the uncertainty band |
| Registered radio age | Median **7.645 s**, P90 13.703 s; maximum 15.784 s | Only 748/2,185 aged ≤5 s; only 131 aged ≤1 s |
| Paired callback gap | Median 419 ms; P90 2.831 s; maximum 4.206 s | All 305 join within 5 s, only 205 within 1 s; temporal overlap does not prove clock agreement |
| Cross-phone clock error | No measured offset or synchronization event in exports | Mark unknown. Do not derive caller latency by subtracting two-phone timestamps |
| Fix versus callback | `elapsedRealtimeNanos` is fix time; `recordedAtMillis` is callback wall time | Location, radio and context at a callback need not represent the same instant |
| Target observation policy | Screen kept on; foreground service and high-accuracy location requested every second | Existing sessions cannot test screen-off behavior; logging itself needs an intervention control |

Android documents that `getAllCellInfo()` on apps targeting Android Q or later returns cached information and does not trigger refresh; even explicit refresh requests are rate-limited and not guaranteed. This fits the source's cached API use and the measured repetitions. [Android TelephonyManager reference](https://developer.android.com/reference/android/telephony/TelephonyManager#getAllCellInfo()).

Source inspection found no explicit app WakeLock or battery-optimization exemption. That does **not** prove the active logger is neutral. Screen off, Android device idle, telephony idle and human inactivity are separate states. [Android Doze documentation](https://developer.android.com/training/monitoring-device-state/doze-standby).

For a new pilot, record automatic-time configuration and a bounded clock comparison before/after collection. Associate two-phone events with an uncertainty interval; exclude boundary events whose classification changes within that interval. A recent target callback plus a fresh modem reading improves validation but does not reveal an exact cellular transition. Do not reintroduce a synchronization implementation merely to claim millisecond truth.

### 2.6 Previous negative findings retained

| Finding | Evidence and denominator | Limit on interpretation |
|---|---|---|
| Target Wi-Fi condition failed replication | Day 1 medians A1/B/A2=754.5/1265.5/759.5 ms. Day 2=678/823/761 ms; unchanged-A-extra=847 ms. B versus A-extra exploratory p=.868 | Latency/path differences can occur, but no replicated Wi-Fi classifier; correlated probes are not independent blocks |
| Extra SIP-field patterns failed | Contact/SDP origin, Record-Route, media-address pools and response-source patterns changed even without the target condition changing | Do not revive them through a pooled ML split; newly hypothesized states need new controls |
| Short inactivity comparison was negative | Handoff reports about 727 versus 725 ms for ~30 s waiting versus rapid probing | Original paired experiment is not identified in the nine exports. Retain as handoff-level negative evidence, not a newly reproduced causal power-save result |
| Earlier movement/latency indication inconclusive | Handoff says unbalanced motion data and radio-quality confounding | Cannot label the current speed bins as verified movement or claim a movement latency effect |
| Raw cell changes occur while stationary | Seven blocks: 5 alerts using three consecutive new raw cells; 0 with the existing prefix grouping | Static specificity only; a detector that never alerts would also pass this control |
| Header equality is not current-serving equality | 212/212 parser/captured-header agreement; fresh target comparison exact **45/76**, prefix **73/76** | Mismatch does not identify stale CNI by itself; target caching, asynchronous observations and clock error compete |
| Point accuracy did not yield calibrated confidence | Day-held-out stationary benchmark: 94/97 estimated; median error 52.5 m; **0/94 inside claimed 90% radius** | No route/geofence precision claim; a small site shift can break an overconfident training radius |
| Historical online geolocation failed | All 212 seven-block lookups HTTP 404; root-level radioType corrected later in source | Live outcome unverified; no external re-query occurred during discovery |

Primary local sources: [SIP observability results](SIP_OBSERVABILITY_RESULTS.md), [cell sequence study](CELL_SEQUENCE_STUDY.md), and the supplied project handoff. The broad-window cell comparison (134/212 exact, 201/212 prefix at 30 s) is a sensitivity analysis admitting older truth, not stronger validation. The expanded paired diagnostics yield 49/81 exact and 77/81 prefix with fresh eligible truth; they do not replace the predefined seven-block result.

## 3. Literature and observer-access map

All technical claims below use original studies or issuing standards bodies. Dates refer to the publication/version, not search-engine crawl dates. Important closest sources were read beyond their abstracts; access failures are disclosed in §8.

| Reference and date | What it establishes; access assumptions | Consequence for this project |
|---|---|---|
| [SSTIC caller disclosure paper, Ventuzelo/Le Moal/Coudray, 2017](https://www.sstic.org/media/SSTIC2017/SSTIC-actes/remote_geolocation_and_tracing_of_subscribers_usin/SSTIC2017-Article-remote_geolocation_and_tracing_of_subscribers_using_4g_volte_android_phone-le-moal_ventuzelo_coudray.pdf), §§5.1–5.3 | Caller-visible first-183 callee IMEI/Contact and PANI cell disclosure; registration responses also expose network fingerprints. Rooted caller instrumentation; studied deployed configurations | First-183 location and generic extra-identifier disclosure are old. PANI versus CNI is a mechanism difference, not sufficient novelty |
| [O2 original disclosure, Daniel Williams, 17 May 2025](https://mastdatabase.co.uk/blog/2025/05/o2-expose-customer-location-call-4g/), update 19 May | Caller CNI and vendor identifier leakage, including a roaming example; author says he verified the fix | CNI-specific basic disclosure also has prior art. Firsthand disclosure lacks a systematic freshness/GPS error study; its precision language is not adopted |
| [Ghost Calls, Lu et al., MobiCom 2020](https://xietian1.github.io/paper/Mobicom2020.pdf), §§5.1–5.2 | Instrumented caller's initial SIP content/flow and 100-to-183/180 timing classify broad call technology/setup-state groups. Over 5,000 traces; four carriers; 60/40 split in principal datasets | Closest prior to extra caller inference. VoWiFi/VoLTE-ready were not separable; telephony idle/talking were combined. No screen-state result or explicit day-separated evaluation in that section |
| [CAPS, Paek/Kim/Singh/Govindan, MobiSys 2011, original paper](https://cseweb.ucsd.edu/~schulman/class/cse291_w17/docs/paek_caps.pdf), §§4–6; [conference record](https://www.usenix.org/conference/mobisys-2011/energy-efficient-positioning-smartphones-using-cell-id-sequence-matching) | Cell-ID history matched to personal GPS-associated route histories; approximately 2.5 s actual samples, repeated training routes and runtime GPS fallback | ID-only sequence matching is established. Equal sequences can represent different routes. Neither its precision nor GPS energy saving transfers to sparse remote CNI |
| [Cell*, Leontiadis et al., CoNEXT 2014](https://conferences.sigcomm.org/co-next/2014/CoNEXT_papers/p121.pdf), §§2–5 | Cell sequence movement segmentation and path estimation without RSSI/neighbors, using operator sector topology/orientation/coverage and GIS | Stronger prior than “cell changes imply motion.” Stationary sector changes and unobserved same-coverage trips are documented; those richer inputs are unavailable here |
| [Combining GPS and GSM Cell-ID positioning, Deblauwe/Ruppel, MobiQuitous 2007](https://www.researchgate.net/publication/4318531_Combining_GPS_and_GSM_Cell-ID_positioning_for_Proactive_Location-based_Services), §§I–IV; [publisher record, 12 Feb 2008](https://eudl.eu/doi/10.1109/MOBIQ.2007.4450985) | Cooperative current-cell/GPS terminals, known cell geometry and event reporting; simulation studies zone/proximity events and GPS/message-count tradeoffs. Cell strategies can increase messages versus its GPS-only comparator | Zone events and reporting tradeoffs are already researched. The open question here concerns caller observation without live target GPS fallback or operator coverage |
| [Careless Whisper, Gegenhuber et al., RAID 2025](https://eprints.cs.univie.ac.at/8483/1/RAID_2025_Careless_Whisper_Exploiting_Silent__Gabriel%20Gegenhuber.pdf), §V-C/Appendix A4; [preprint, 17 Nov 2024](https://arxiv.org/abs/2411.11194) | Remote messenger delivery-receipt timings reflect screen/app/device conditions. Cadence effects are device dependent; frequent probing can obscure standby | Motivates B and its intervention controls. Messenger endpoint receipts are not proof of equivalent IMS behavior |
| [Freaky Leaky SMS, Bitsikas et al., USENIX Security 2023](https://www.usenix.org/system/files/usenixsecurity23-bitsikas.pdf), §§3–6 | Sender delivery-report timing and location-labelled training distinguish some known locations; device/carrier/time variation evaluated | Remote timing localization itself is established. SMS acknowledgement and first IMS provisional have different paths |
| [Hope of Delivery, Bitsikas et al., NDSS 2023](https://www.ndss-symposium.org/wp-content/uploads/2023/02/ndss2023_s188_paper.pdf), §§III–VIII | Sender messenger delivery-receipt timings use known-location training; access network and VPN conditions affect the timing path | Followed from related timing work. Location fingerprints do not establish physical ranging or a screen-state signal on IMS |
| [The fallacy of the closest antenna, Ogulenko et al., 2022](https://www.sciencedirect.com/science/article/pii/S0198971522000709); [author preprint, 2021](https://arxiv.org/abs/2109.02154) | Serving-cell/GPS volunteer study challenges nearest-antenna/Voronoi assumptions | Tower coordinate is not target coordinate; its particular percentage does not generalize to this carrier |
| [MonoSense, AbdelAziz/Youssef, 2015](https://arxiv.org/abs/1502.01454) | Transportation classification uses serving cell ID **and RSSI**, despite “single cell” wording | Target RSSI is unavailable to the caller; reported movement-mode accuracy does not transfer |
| [Watching Your Call, 2023](https://arxiv.org/abs/2301.02487), §3.1 | Radio observer/SDR mobile relay sees target scheduling and traffic | Extra radio vantage; calling a number in one variant does not make the observer caller-only |
| [When Good Turns Evil / CrossIL, CNS 2023](https://xietian1.github.io/paper/Shi-CNS23.pdf), §III | Near-target SDR observes encrypted radio traffic; identity-linkage variant adds camera observations | Speaking/listening and target-radio state cannot be credited to our pre-answer caller signaling |
| [The Dark Side of Operational Wi-Fi Calling Services, CNS 2018](https://www.cse.msu.edu/~ghtu/cns18.pdf), §II | Same-subnet observer monitors target calling-tunnel traffic patterns | Same Wi-Fi/subnet access is materially different from a remote caller observing its own transaction |

### 3.1 Standards that limit interpretation

- **CNI:** most recently camped cellular identity on noncellular access; age reflects UE collection with implementation/policy-dependent calculation. Standardized proxies must not modify its value, and the specification expects trust-boundary removal and no reception at a UE. This supports a fidelity check; it does not establish which deployed component generated a particular observed value. [TS 24.229 V19.7.0, August 2026, §7.2.15 and Annex R.3.2.1](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.07.00_60/ts_124229v190700p.pdf).
- **SIP progress and errors:** 183 is session progress, not proof of ringing or human action. Proxies/gateways can respond; 480 and other broad errors do not uniquely identify power-off, DND or a disconnected access network. [RFC 3261, June 2002, §§11/21](https://www.rfc-editor.org/rfc/rfc3261.html).
- **Source attribution:** B2BUAs and media relays can replace headers, SDP and addresses. Contact, codec sets, Server strings and transport source need origin checks before they describe a target device. [RFC 7092, December 2013, §§3/4](https://www.rfc-editor.org/rfc/rfc7092.html), [RFC 5379, February 2010, §5](https://www.rfc-editor.org/rfc/rfc5379.html).
- **Feature tags/instance IDs:** capability and identity mechanisms exist, but may be suppressed or rewritten. [RFC 3840, August 2004](https://datatracker.ietf.org/doc/html/rfc3840), [RFC 5626, October 2009](https://www.rfc-editor.org/rfc/rfc5626.html).
- **PANI:** UE/network-provided access information and permitted response use do not guarantee caller disclosure. Current usage includes later updates. [RFC 7315, July 2014](https://datatracker.ietf.org/doc/html/rfc7315), [RFC 9878, November 2025](https://www.rfc-editor.org/rfc/rfc9878.html).
- **ECI grouping:** the project's `ECI >> 8` is a **20-bit prefix grouping**, consistent with a Macro eNB assumption. Other eNB types use other identifier lengths. Until type/topology is established, call it a prefix, not a measured physical tower. [TS 36.413 V18.1.0, §9.2.1.37](https://www.etsi.org/deliver/etsi_ts/136400_136499/136413/18.01.00_60/ts_136413v180100p.pdf).
- **Enhanced Cell ID:** additional UE/network ranging and radio measurements underpin E-CID. LocationData recording some metrics does not add them to CNI. [TS 36.305 V16.5.0, §8.3.1](https://www.etsi.org/deliver/etsi_ts/136300_136399/136305/16.05.00_60/ts_136305v160500p.pdf).

The optional `Response-Source` standard concerns **error** response origin, not generic first-183 attribution; it is absent locally. [TS 24.229 V19.7.0, §7.2.17](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.07.00_60/ts_124229v190700p.pdf). Standard behavior and deployed observation are separate evidence. Do not infer normal proxy rewriting of CNI from the general B2BUA discussion.

## 4. Twelve candidate questions and rejection decisions

The three starred questions were investigated deeply. Related methods can be shared, but each question below has a distinct desired output. A and C were initially consolidated; the subsequent user challenge withdraws A as preferred (§6.2).

| # | Exact question / additional capability | Reuse versus new evidence | Evaluation and disposition |
|---|---|---|---|
| **A★** | Can caller CNI history detect departure from a predefined area with useful delay and explicit unknown outcomes? | Static controls reusable; labelled departures/returns and access-loss controls absent | Withdraw as preferred: known-site lookup does not transfer to unseen places; target-Wi-Fi loss may end cell observation |
| **B★** | Can caller first-response timing distinguish screen on/off **before** the request under fixed access and controlled quiet time? | Timing/noise reusable; screen-state contrasts and observer controls absent | Unverified hypothesis: distinct extra state possible, target-dependent and transferable timing unproved |
| **C★** | Can uncertain CNI history yield a smaller, empirically calibrated location region than the latest cell alone? | Existing calibration failure reusable; independent spatial survey required | Deferred diagnostic; surveyed-domain success does not establish unseen-place use |
| 4 | Can initial SIP reveal target call technology or another call being set up? | Current first-183 path only; controlled call states absent | Closest Ghost Calls already establishes grouped cases. Generic replication lacks contribution; talking versus idle remains ambiguous with call waiting |
| 5 | Can caller Contact/SDP/feature tags identify target device family, firmware or a persistent instance? | Existing wire availability can be checked now | Identifiers/fingerprinting already known; relevant Contact parameters absent, and relays confound device attribution |
| 6 | Can headers/timing identify a target Wi-Fi change while its cellular footprint stays the same? | Existing two-day A/B/A is direct evidence | Reject as leader: zero replicated additional feature sets; different hypothetical state must be specified before reopening |
| 7 | Can age/history distinguish current cellular availability from a past cellular footprint? | All existing ages zero; cellular-loss controls absent | Fold into A's validity checks; age is not disconnection duration, and no current discriminating evidence |
| 8 | Can caller latency/CNI reveal weak radio signal, obstruction or cellular-link quality? | Target metrics available as controls; controlled RF contrasts absent | No caller target-RSSI/TA; caller latency includes Wi-Fi/IMS. Not supported as a distinct useful inference |
| 9 | Can caller observe access handover or reconnect without physical motion? | Transitional target Wi-Fi samples exist; no exact IMS/RRC event trace | Defer: current first-response cutoff may miss events; a transport change is not verified IMS handover |
| 10 | Can errors/absence distinguish powered off, airplane mode, DND and lost Wi-Fi? | Existing errors sparse; labelled state interventions absent | Generic reachability known; many-to-one responses and routing policy defeat unique state claims |
| 11 | Can response/SDP IP identify target public IP, Wi-Fi provider or physical region? | Recorded path addresses present | Reject current premise: target-origin address not established; previous path patterns failed replication |
| 12 | Can caller signaling reveal other messages/calls, speaking or specific app activity? | Required target traffic is not in caller captures | Reject under scope: closest radio/tunnel/media studies use a different observer or call content |

AI is a possible analysis tool for A/B/C, not a thirteenth research question. It cannot create unavailable measurements. Use simple baselines first; add a model only for a reproducible residual effect, with day/episode splits, label-shuffle checks and identifier leakage checks. No new pooled classifier is justified by the current Wi-Fi negatives.

## 5. Three deeply investigated finalists

### 5.1 A — Departure inference under sparse, possibly historical CNI

**Revised disposition: withdrawn as the leading direction.** The following design tests a surveyed area while preserving access. Its Day 1 lookup cannot label arbitrary unseen cells; its Day 2 split does not test unseen geography. Unknown outputs honestly expose this limitation but do not repair it. The user-reported CNI loss outside Wi-Fi introduces another blind interval exactly where an ordinary departure may occur. Missingness cannot be interpreted as departure without distinguishing stationary access failures. The method retains limited known-site diagnostic value; a travelling hotspot would not resolve the ordinary-access application limit.

**Exact question.** For an explicitly surveyed outdoor area, can caller-only CNI history distinguish departure from remaining inside, within a useful deadline, while controlling false alerts and stating unknown outcomes? Does it improve on the latest-cell and existing three-consecutive-prefix rules?

**Pilot operating assumption:** a circle of **200 m radius** around an authorized reference site, with a **120 s alert deadline** after an independently verified exit. These are exploratory defaults, not requirements supplied by the user or a claim that a building/room boundary is detectable. Check access coverage with existing equipment before freezing the actual private boundary and choosing scored routes. If stable access cannot support 200 m, predeclare a smaller boundary within verified coverage and report its actual size; that does not test the 200 m default. If the intended application later requires another boundary, test that boundary explicitly.

**Practical benefit.** A caller could obtain an actionable coarse departure alert, or know that a location assertion has become unsupported. The study can measure whether a slower schedule preserves enough information. It cannot infer “has not moved” from an unchanged cell or justify stopping all future observations.

**Closest work and remaining contribution.** CAPS already matches cell histories; Cell* already segments stationary/moving sequences; Deblauwe/Ruppel already studies zone events and GPS/message-count tradeoffs. A differs in its **caller-visible last-camped reporting channel**, sparse attempts, missing headers, unavailable live GPS fallback, and possibly invalid freshness reports. The contribution would be the event-level inference/unknown/delay/budget frontier and an explanation of fidelity failures, not a new generic geofencing algorithm. See the original-source links and access comparisons in §3.

**Supporting evidence.** The current caller actually obtains CNI, numerical parsing is verified, and coarse prefixes suppress false changes in the fixed controls. Fresh registered measurements and manual events are supported by LocationData, though actual radio freshness must be checked.

**Contrary evidence.** The exact cell often disagrees with nominally fresh target truth; age is invariant zero; same-coverage exits can preserve all caller inputs; no labelled positive exits currently exist. Prefix stability could be a detector that simply never fires. Cell* and CAPS document spatial ambiguity, so this known limitation alone is not a new contribution.

**Inputs and competing explanations.**

| Role | Measurements |
|---|---|
| Permitted caller predictors | PLMN/TAC/ECI, observed RAT/age from native CNI, prefix under stated assumption, past probe times, header missingness and completed transaction outcomes |
| Target truth | Predeclared boundary, manually recorded departure/return and fused position with quality/age; registered cell and its actual measurement time for fidelity evaluation |
| Controls | Same target Wi-Fi/hotspot, caller position/access, builds/settings, screen/charging policy, IMS/process reconnects; target neighbors/signals as RF diagnostics only |
| Not available now | True labelled departures, complete neighbor global IDs, exact RRC/TAU/paging/target IMS event times, measured two-phone clock error |

Competing explanations for a caller cell change are reselection without motion, stale reporting, earlier radio history, changed access/IMS path, target cache, clock error and process/session artifacts. Competing explanations for **no change** include no movement, movement inside coverage, historical unchanged reporting, missed attempts and the sampling interval. Missing CNI is unknown evidence, not an exit.

#### Previous small pilot A (retained for review; not currently recommended or run)

1. **Reconnaissance without fitting scored outcomes.** Define the geographic boundary and outdoor landmark points, then use the authorized target to find two feasible departures: a short exit likely to retain a prefix, and a longer exit likely to change it. Keep both in the denominator even if the target-only expectation is wrong. Radio-defined route selection makes the pilot purposive; it is not a random sample of everyday departures.
2. **One access configuration throughout, using current resources first.** Use a fixed existing AP and verify that all inside/exit/return routes retain its coverage without access changes. A portable hotspot may accompany the target only if an independently available authorized hotspot lets the caller remain fixed. The handoff establishes two phones and a phone-hotspot condition, but does not establish hotspot ownership or a third device; the travelling-hotspot arrangement is therefore not confirmed inventory. If no suitable routes are feasible with current access, stop this collection proposal and discuss the alternative or resource expansion. Keep APK/native/target versions, SIM, cancellation policy, one-provisional limit and 10 s minimum fixed; archive manifests. Separate and retain interrupted episodes.
3. **Two days; approximately 60–90 minutes each.** Per day: three 8-minute inside stationary controls, three short leave/return episodes and three longer leave/return episodes. Each moving episode includes an inside baseline, at least 120 s confirmed outside, and return; use outside dwell portions as additional controls for spurious repeated events. Counterbalance short/long order and control placement. Do not join multiple loops as independent probes.
4. **Independent truth.** Record manual phase events plus position. A scored exit requires a fresh fix (≤5 s) with reported accuracy ≤20 m outside the boundary plus a predeclared 20 m guard band, supported by an outdoor landmark/manual observation. Ambiguous boundary crossings receive an event-time interval; report them separately rather than letting Cell ID define truth. Report usable and excluded episodes with reasons. Accuracy circles are quality flags, not guaranteed confidence bounds.
5. **Fidelity before model fitting.** Match CNI to explicitly registered target history using modem measurement timestamps, not only callback time. For a response-time fidelity label, the entire plausible target measurement interval must precede the matched first 183, with age ≤5 s **at that response**, after accounting for clock uncertainty. For a pre-probe claim it must precede INVITE. Exclude later measurements and ambiguous time intervals from current-cell fidelity labels, even if the callback's age is small. Sensitivity uses 1/30 s and bounded clock shifts. Preserve these episodes for geographic event scoring when independent event truth remains valid. Do not impute real cellular transitions from two repeated cached snapshots.
6. **Freeze comparisons.** Baselines: always silent; latest raw-cell change; existing three-consecutive-prefix change. The candidate is a simple known-area cell/history rule trained on Day 1 paired truth, with unknown for unsupported cells, long gaps and ambiguous histories. Use the same training information for latest-cell and history models. Day 2 is untouched until thresholds/windows/expiry are frozen. No session IDs, absolute-time labels, IPs or target current telemetry enter prediction.
7. **Score every eligible held-out exit.** Report detected exits by 120 s, misses, late alerts, false alerts per stationary hour, time/probes to alert, attempted versus visible probes, and unknown-time fraction. Give short/same-prefix and longer-exit results separately and combined. An exit still unknown at 120 s is a miss; unknown is not a reason to remove it. Count a departure once, and report repeat alarms separately.
8. **Probe budget only after discrimination.** Replay fixed 20/30/60 s schedules with different phase offsets, counting actual attempted and visible observations. An adaptive rule may use only past caller inputs and must keep a bounded maximum gap. Replay estimates information lost by thinning; it cannot prove the effect of actually probing less because requests can change handset/path state. Prospective slower-cadence replication is a later gate.

**Pilot continuation rule.** With all six Day 2 departures scorable, require at least **two of three timely detections in each exit stratum**, no false alerts in the designated held-out inside controls, and useful output coverage. If truth/recording failures leave fewer eligible episodes, repeat the incomplete collection; do not declare a pass. Compare against the strongest prefix baseline: a more complex history rule earns further work only if it adds timely detections without extra false alerts, or removes false alerts without losing timely detections. Report the counts even when the comparison is inconclusive.

This is a screening rule chosen before collection, not a validated 67% reliability target. Six held-out events are too few for a strong recall claim. Zero alerts across the combined 48 minutes of designated inside controls gives, under a Poisson approximation, a 95% upper rate of roughly **3.75/hour**; Day 2 alone is weaker. Correlation may invalidate that model. Rare false-alert claims require much longer independent exposure and multiple days.

**Abandon or revise.** Stop the selected-boundary direction if short departures repeatedly have indistinguishable inputs through the deadline, static conditions produce similar alerts, or the method returns unknown on most departures. If only long departures work, discuss a narrower coarse-region application; do not call it success for the original boundary. If additional history gives no advantage, stop added model development. If all findings merely reproduce known cell ambiguity with no new channel-fidelity or operating-limit result, the research-contribution gate fails even if a baseline is operationally convenient.

**Replication needs and uncertainty.** A positive pilot needs further independent days, a second boundary/site, longer static exposure, access-change controls and eventually another target/carrier. Validate observer effects: keep target logging policy identical across arms initially, then repeat key manually verified routes without active radio/location logging before claiming operation without it. No population confidence, whole-cell coverage model or precision guarantee follows from the pilot.

### 5.2 B — Pre-probe screen state through caller response timing

**Exact question.** At the same place, AP and deployed IMS path, does the first response after a quiet episode distinguish **physical screen on versus off immediately before the request**, beyond quiet duration and caller/path context? Does the distinction survive target-logger removal and a separate-day test?

**Practical benefit.** This would add a coarse device state to location observations and could inform consent-based device diagnostics or measurement scheduling. It would not establish attention, human presence, a specific application, sleep, immobility, CPU sleep or Android Doze.

**Closest work and unresolved contribution.** Ghost Calls already studies caller IMS timing and grouped telephony states. Careless Whisper demonstrates messenger screen-dependent receipts and measurement interference. The unresolved question is an **IMS-specific, pre-intervention screen-state signal**, its causal controls, and its stability with ordinary caller observations. Their results support a hypothesis, not transferred accuracy; telephony idle is not screen off. Original sources and sections are in §3.

**Evidence balance.** Caller timing and stage decomposition exist. However all saved target samples are interactive with screen keeping enabled; there is no current positive screen contrast. Previous short waiting and Wi-Fi timing comparisons were negative. Priority IMS processing, modem separation, early network responses, logger activity and incoming-request wake effects can erase or replace a screen effect.

| Role | Required measurements and limits |
|---|---|
| Primary caller feature | INVITE-to-first-183 monotonic delay; retain timeouts/missing responses rather than selecting successful fast calls |
| Prespecified secondary feature | 100-to-first-183 where matched timestamps exist; header/flow/path changes are diagnostics, not identity features |
| Target truth | Assigned screen condition and externally verified physical display state before request; PowerManager interactive/idle records only in instrumented arms |
| Observer control | Logger enabled with keepScreenOn disabled versus logger stopped; fixed position, Wi-Fi, charging, background workload and call state |
| Origin evidence | Existing target-side SIP event capture only if actually available and safely attributable; current LocationData provides no IMS reception timestamp |

A randomized screen intervention can test a state-conditioned effect without proving which IMS component explains it. CNI presence or a displayed incoming-call UI alone does not prove where the first 183 originated. If first-response target involvement cannot be verified, keep origin unknown and avoid a target-CPU explanation. A verified upstream response insensitive to target processing is a reason to stop this event-based hypothesis.

#### Small pilot B (proposed; not run)

1. **Measurement-validity check before a classifier.** At one fixed site/AP, disable the existing keep-screen-on option. Use externally observed screen conditions in a crossed screen-on/off × logger-on/off design. Screen off means the physical display is off, including always-on/ambient display disabled; screen on uses a fixed visible page without user interaction. Verify immediately before each request. Do not reopen the target app, query it continuously or attach a charging cable just before a quiet trial. Record the state transition and first request using the caller's clock; physical observation supplies logger-off labels. PowerManager interactive state is supporting context, not an exact physical-display label.
2. **Quiet-time feasibility.** Suspend automatic probing and auto-restart throughout each quiet window, then issue one ordinary request. Verify that this operation is feasible before scheduling the trials; do not assume an existing pause feature. Preserve caller IMS/process state where feasible; otherwise balance and document the restart/reconnection procedure across screen labels. Test 30 s and 5 min without incoming probes, two independent episodes per crossed condition/duration: **16 first-post-quiet trials**. Both screen conditions wait equally long. These durations are test settings, not claims that Doze has been reached. Add matched quiet episodes without a probe to distinguish natural screen changes from request-induced changes. If actual pre-request state or ordinary cleanup cannot be verified, stop and repair measurement before fitting.
3. **Independent-day confirmation only after an effect.** Select and freeze one feasible quiet duration and the primary timing decision from the feasibility phase. On a different day, collect four randomized trials for each screen × logger condition: **16 scored first-post-quiet observations**, eight per screen class. Spread trials across independent quiet episodes, with unchanged-state repeats and counterbalanced order. Budget roughly 1–2 hours for each phase if the longer duration is used.
4. **Intervention outcome separate from prediction.** Record whether the first request lights the screen or changes subsequent timing. A second ordinary attempt no sooner than the established 10 s minimum can be an exploratory warm-state check on a small predefined subset; never treat it as another independent pre-quiet sample. Keep cancellation/completion behavior bounded and unchanged.
5. **Confound controls.** Caller remains in a stable context. Check INVITE-to-100, network/path changes and process restarts as nuisance diagnostics. Target Wi-Fi queries/logging occur only in the logger-on arm or outside quiet windows; the logger-off arm has no hidden continuous target query. Randomize labels across time; do not feed source addresses or session IDs into the model. Compare with a quiet-duration/caller-context-only baseline. A common delay in both timing stages is not proof of target wake-up.
6. **Outcomes.** Report distributions/effect uncertainty, held-out balanced accuracy, class recall, timeouts, omitted trials with reasons, and logger/probe interaction. Primary threshold is fixed before confirmation. A block/episode-level label shuffle and the nuisance baseline must not reproduce the state association.

**Continuation rule.** As a pilot screen, require at least **12/16** correct held-out predictions, at least **6/8** in each screen class, and no collapse to chance in the logger-off arm (at least 6/8 there), with the same direction of the timing effect on both days. Include incomplete/time-out trials as unknown/failed predictions in the denominator. This small result would justify replication, not establish a reliable classifier; report uncertainty rather than only an accuracy percentage.

**Abandon or revise.** Stop if actual screen contrast cannot coexist with valid observation, first-response timing is verified upstream of target involvement, the held-out effect matches the nuisance baseline/chance, or it occurs only with the logger active. A probe-induced wake effect is an instrument effect, not evidence of the pre-probe state unless the first observation carries independently reproducible information. If the effect survives only one handset/network, require replication before practical or general claims.

**Replication needs/uncertainty.** Further independent quiet episodes, an additional day, another target, different background-load/charging conditions and another access path are required. Manual state controls make a measurement proposal possible, but **endpoint origin, useful discrimination and transfer remain unverified**; this is insufficient to promote B as the preferred research direction. Do not require unknown privileged target instrumentation as a hidden prerequisite or infer Doze from a dark screen.

### 5.3 C — Calibrated spatial regions from uncertain CNI history

**Exact question.** With a locally authorized reference survey and only caller history at prediction time, can a history-conditioned region attain useful held-out containment with less area than a latest-cell region, despite ambiguous/recently camped reports?

**Benefit.** A calibrated region would make location statements safer and could support inside/outside/unknown in A. An honest larger region may improve correctness while providing no extra spatial capability; both containment and size matter.

**Closest work/contribution uncertainty.** CAPS and Cell* already exploit multiple cell observations; nearest-tower assumptions are challenged by the closest-antenna study. A genuine difference would require quantitative calibration and useful spatial information on the remote reporting channel. Calibration terminology, another ML model, or repeated training-site positions do not establish novelty.

**Evidence balance.** The existing 0/94 nominal-radius containment is a concrete reason to reject the current confidence method. Conversely the data do not cover a cell's spatial footprint, operator coverage/topology are unavailable, neighboring cells lack global IDs, and no successful historical API baseline exists. The prospective method cannot promise a smaller region from more repetitions of the same unchanging identifier.

**Measurements/confounds.** Caller inputs are the same CNI history as A. Target positions, quality and registered radio timestamps support survey/validation only. Spatial sampling bias, site/day correlation, unknown cells, stale radio, fused-position errors, model overconfidence and always-emit-everywhere behavior compete with apparent precision gains.

#### Small stress test C (secondary; not a separate recommended campaign)

If collected with A, use three privately surveyed landmark areas, including an inside/outside pair sharing cells, and two days. Retain independent visits, matched access and enough fixed dwell to observe reselection. Train latest-cell and history methods on identical Day 1 information, freeze them, then score Day 2 familiar-site visits. Separately leave one **site** out to expose unsupported spatial transfer. These tests answer different questions; never describe a known-site day split as unseen-location generalization.

Report empirical containment for a nominal 90% region, region area, point-error tails, fraction of visits with an emitted region, unknown-cell frequency and failures by site. Count abstentions in overall usefulness; report conditional containment separately. Use an empirical latest-cell region as the working baseline, not the broken historical online request. A sample of three sites can expose overconfidence but cannot calibrate a whole coverage surface.

**Stop/revise.** Defer standalone work unless a same-input comparison improves containment without expanding regions to essentially the whole surveyed domain or abstaining on most independent visits. Stop precision claims if shared-cell sites cannot be separated or held-out site coverage fails. Additional spatially diverse survey visits and independent days would be required before any nominal-confidence claim.

**Disposition.** The independent reviewer rejected C as a standalone finalist. Uncertainty/containment checks retain diagnostic value if local data are analyzed later; they do not justify reviving A or establish an unseen-place capability.

## 6. Independent challenge and resolutions

A separate reviewer checked the audited measurements and attacked all three finalist briefs using the linked original sources. **None received unconditional acceptance.** The initial review retained A and B as bounded feasibility pilots and rejected C as a separate project. The later user challenge and independent reassessment (§6.2) withdraw A as preferred and leave B unverified. The initial objection table below is retained as a review trail, not the current qualification result.

| Objection | Initial review resolution | Still uncertain |
|---|---|---|
| Zone exits, cell-history inference and reducing updates already exist | Explicit prior-work comparison; A's contribution restricted to remote reporting fidelity and the useful event/error/delay/budget tradeoff without live target fallback | Publication novelty must be reassessed against actual pilot findings |
| A stable prefix can simply never alert | Always-silent baseline, true exits and timely recall required; unchanged history cannot certify staying | Whether the channel provides enough information at the chosen boundary |
| Only routes chosen to change cells make the experiment easy | Short exits kept alongside longer exits; primary strata and boundary chosen in advance; purposive-selection limit stated | Representativeness requires later sites/routes |
| Moving target alone using a hotspot confounds movement with access | Same AP/access policy for moving and matched static conditions; fixed-AP coverage checked first; travelling hotspot conditional on independent availability | Larger routes may be infeasible with current equipment; IMS/path variability persists |
| Unknown outputs or excluded hard cases can hide missed departures | Unknown through deadline counts as a miss; all truth/recording exclusions and output coverage reported | Small pilot remains statistically weak |
| Brief controls cannot validate rare false alerts | Exposure time and a model-dependent upper bound stated; larger independent exposure required | Dependence may defeat a simple Poisson interpretation |
| Screen logger prevents the state being tested | Screen × logger crossed conditions; external pre-request labels in logger-off arm; no app reopening to verify quiet state | Residual observer/background workload effects |
| Repeated probes warm the target/path | Independent first-post-quiet episodes; later attempts are intervention outcomes, not extra pre-state examples | Longer intervals may still fail to expose a screen effect |
| First 183 is not necessarily target-generated | Origin kept unknown unless verified; no target-CPU explanation or hidden privileged instrumentation prerequisite | Mechanistic attribution remains weaker than a causal randomized screen association |
| Clock/callback freshness can use later observations as truth | Measurement times and clock uncertainty propagated; pre-probe claims need observations entirely before INVITE | Current exports contain no measured cross-phone offset |
| Three sites and a nominal radius do not establish spatial confidence | C becomes a stress test inside A; containment, area, abstention and surveyed-domain limitations required | Whole-cell and unseen-site calibration unavailable |
| A changed SIP path is not a target device fingerprint | No session/IP identifiers as classifier inputs; wire field population checked; previous negative findings retained | Additional states could be observable, but current evidence does not establish them |

### 6.1 Chronology check added after review

Of 305 paired nearest joins, **301** have a response timestamp: 138 target callbacks occur after it and 163 before it under saved wall clocks. Four attempts use fallback INVITE/recorded time and cannot be described as response-time joins.

Of the 81 expanded CNI comparisons satisfying radio age ≤5 s **at callback**, 41 callbacks follow the response; the inferred modem measurement wall time (`recordedAtMillis − ageMillis`) follows the response in **3**, precedes it in 78. These are saved-clock relations, not proven physical future measurements: offset and timestamp lag remain unknown. Existing fresh-match ratios therefore do not certify contemporaneous truth. Prospective pre-probe state scoring requires the whole plausible observation interval before INVITE; event-alert delay must include clock and boundary-time uncertainty.

### 6.2 User challenge and independent reassessment

The user identified two application limits that the initial qualification decision underweighted:

1. A location-labelled Cell ID lookup requires prior observation at that area. A new day at the same site cannot demonstrate new-place transfer. Repeated unfamiliar IDs remain geographically unlabelled; adding a temporal threshold or AI does not resolve that missing relationship.
2. The user reports no Cell ID on this deployed path once target Wi-Fi is lost. An ordinary departure can therefore end observation before the proposed cell-history detector has evidence. A stationary Wi-Fi disconnect can produce the same missingness. A travelling hotspot preserves access under a different operating condition rather than validating ordinary AP departure.

The second point is a user-reported operational constraint, not a transition reproduced in the nine stationary exports. Record it as a prerequisite to check, without assuming that every carrier/IMS path behaves identically.

The independent reviewer agreed to **withdraw A as preferred**, retain only limited known-site diagnostic value, and **not automatically promote B**. All saved samples are interactive; target involvement in first-response timing and useful state discrimination remain unverified. The honest current result is no preferred capability direction meeting the agreed contribution and practical-feasibility bar. A protocol that can be measured is not sufficient evidence that it is worth pursuing as the leading research question.

## 7. Revised qualification and next decision

| Required gate | A: known-area departure | B: screen-state timing |
|---|---|---|
| Prior-work review | Closest sequence/sector/zone-event originals read; broad novelty rejected | Closest caller IMS and messenger timing originals read; no novelty claim |
| Counterevidence search | Cached truth, same-coverage ambiguity and fixed-point false changes retained; user adds unseen-place and Wi-Fi-loss objections | Network progress, logger interference, probe waking and previous timing negatives retained |
| Measurement feasibility | Local caller/target streams exist, but preserving access is a restricted operating condition; observation after ordinary AP loss is not established | Timings exist; manual contrasts are possible, but valid quiet operation and informative target-dependent timing need verification |
| Pilot-design check | Retained protocol can test local repeatability; it does not answer unseen-place use or natural Wi-Fi-loss departures | Retained crossed-arm proposal can test a hypothesis; it does not establish a useful or transferable effect |
| Research/practical value | **Fails as the preferred direction under the clarified use case.** Limited known-site diagnostic value remains | **Unproven.** Do not substitute this hypothesis for a qualified recommendation |
| Current disposition | Withdraw as preferred; no new departure collection recommended on this justification | Retain as unverified lead only |

**No preferred capability direction currently qualifies.** Before another application-oriented recommendation, establish the observation channel's actual operating envelope. Then require a signal with useful discrimination under the intended conditions, make site/target-specific training explicit, and evaluate unseen-place transfer if that is part of the application. If current caller observations cannot support those requirements, discuss another device/carrier/access condition or broader channel rather than quietly changing the observer or preserving Wi-Fi artificially.

### 7.1 Possible observation-channel check (prerequisite, not an inference contribution)

Follow-up literature and instrumentation review: [WIFI_LOSS_OBSERVABILITY_REVIEW.md](WIFI_LOSS_OBSERVABILITY_REVIEW.md). It finds conditional transient hypotheses, but no verified unique caller discriminator for stationary manual Wi-Fi off versus walking out of coverage. Optional failure diagnostics identify protocol consequences; both actions can have the same caller-visible history. This does not reinstate the withdrawn departure recommendation.

Use existing authorized equipment and keep caller context fixed. Compare repeated episodes of:

| Target condition | Question answered |
|---|---|
| Stationary, Wi-Fi on → off → on | What caller observations disappear or change due to access alone, without movement? |
| Moving while remaining within Wi-Fi coverage | What changes with motion while the observation channel remains available? |
| Leaving Wi-Fi coverage, then returning | When does observation disappear/resume in an ordinary departure, and how does it compare with stationary access loss? |

Separate **no response**, **response without CNI**, and **response with CNI**. Retain response status, caller monotonic stage timings, CNI identity/age when present, and missingness intervals. Target manual position/access labels and registered radio freshness validate conditions only; current LocationData does not provide exact target IMS transition times. Keep screen/logger, caller access/process and ordinary request/cleanup policy consistent across conditions.

Report the fraction of scheduled attempts in each observable category, disappearance/recovery intervals and their time uncertainty, and overlap between stationary access-loss and departure observations. If the available observations are indistinguishable, this check cannot support a unique departure inference. Differences would be hypotheses for independently replicated follow-up, not automatic classifiers. This check neither resolves unseen-cell geography nor establishes research novelty. New field experiments and app changes remain follow-on work after discussion.

## 8. Source verification, exclusions and audit method

- **Full closest originals read:** SSTIC 2017 §§5.1–5.3; Ghost Calls §§5.1–5.2; CAPS §§4–7; Cell* §§2–5; Deblauwe/Ruppel §§I–IV; Careless Whisper §V-C/Appendix A4; SMS timing §§3–6. Relevant references were followed from SSTIC to the 2015 VoLTE precursor, from CAPS to spatial-trigger work, and from messenger timing work to delivery-report research.
- **CAPS access:** direct browser PDF fetch failed initially. The complete 14-page original at the academic UCSD mirror was retrieved locally and read. The conference metadata/slides alone were not substituted for its evaluation. Its paper has four authors; the linked conference record lists only three.
- **Deblauwe dates and comparison:** conference/year 2007; the linked publisher record gives an online publication date of 2008-02-12. These are different dates, not a new 2008 experiment. Introductory event reporting versus periodic collection is different from its §III-B message-count comparison with GPS-only DCC; the latter does not establish universal message savings.
- **Unread precursor:** [Park et al., BWCCA 2015, original IEEE record](https://ieeexplore.ieee.org/document/7424882/), DOI 10.1109/BWCCA.2015.11, was followed from SSTIC but full text was inaccessible. Its particular cell-disclosure result is attributed by SSTIC, not independently verified here; no recommendation depends on it.
- **Other access limits at the initial review:** Deblauwe/Treu's 2008 hybrid spatial-trigger full text, the Wi Not Calling ACSAC 2018 PDF, and some SIP temporal-fingerprinting full texts could not be obtained. They were leads, not sources for claimed quantitative results. The original Wi Not Calling PDF was subsequently obtained and its link-loss/active-call sections read in [the Wi-Fi-loss follow-up](WIFI_LOSS_OBSERVABILITY_REVIEW.md); its observer and session scope differ from our probes. A publisher abstract confirms older generic SIP fingerprinting, but no unread performance figures are used.
- **Standards:** official TS 24.229 V19.6.0 was checked initially. A direct ETSI directory check found V19.7.0 (August 2026); §§7.2.15/7.2.17 and Annex R.3.2.1 were checked directly in that newer version, which is cited here. The relevant interpretation is unchanged between those checks. Relevant RFC text and TS 36.413 identity types were also checked. TS 36.305's official indexed E-CID section was readable after a direct-fetch problem; it supports only the additional-measurement distinction used here.
- **Excluded secondary interpretations:** news summaries, CVE aggregators, Telia commentary and patents helped find leads but do not establish capability or novelty. O2's synthetic example and strong location-precision language are not treated as measurements. Standards describe expected behavior, not proof of a deployed path or automatic evidence of a carrier's compliance status.
- **Local audit method:** enumerate exported JSON/JSONL, separate schemas and identities, join by the established session mappings, validate manifest hash/size records, inspect incoming INVITE response metadata/header population, count actual nonempty target fields and measurement timestamps, and summarize timing/quality distributions. Raw and sanitized duplicates and six byte-identical tar/extracted archives are not independent observations. The same-file analysis was checked against the existing seven-block totals.
- **Reproduction:** existing baseline commands/configurations remain in [analysis/README.md](../analysis/README.md) and [CELL_SEQUENCE_STUDY.md](CELL_SEQUENCE_STUDY.md). The discovery audit's sanitized memo, read-only reproducer and independent challenge are retained locally at `/tmp/cellid_research_data_audit.md`, `/tmp/cellid_research_data_audit.py` and `/tmp/research_shortlist_review.md`; temporary paths are not permanent archival guarantees. This document records the checked aggregate results and definitions.

This is a targeted primary-source review, not an exhaustive systematic review. Inaccessible work and inconsistent terminology leave novelty uncertainty. Finding no matching paper does not establish novelty. Discovery sent no private cell identifiers or coordinates to external services and ran no new phone probes.

## 9. Search log

All searches below were performed on **2026-10-06**. These are exact query strings recorded by the lead and two literature reviewers; repeated or overlapping queries are retained. Searches included evidence against each hypothesis, already answered questions, and different observer/training assumptions. Search results were leads; §3 and §8 identify the primary material actually used and the access failures.

### 9.1 Cell metadata, sequences, spatial events and prior art

The S1–S10 shorthand in this search lane denotes, respectively: SSTIC disclosure, O2 disclosure, CNI standards, CAPS, Cell*, closest-antenna study, MonoSense, eNB identity standard, E-CID standard, and Deblauwe/Ruppel spatial-event work. Park's inaccessible precursor is S11. These labels describe the search trail, not additional independent evidence.

| Queries | Outcome |
|---|---|
| `"Cellular-Network-Info" VoWiFi location research`; `VoWiFi remote location privacy SIP cell identity research`; `"cell-info-age" location security` | O2 original disclosure, TS 24.229, regulatory and patent leads. |
| `IMS VoWiFi SIP "location" "CNI" security paper`; `"Telia" "SIP" "location" disclosure 2026`; `"Cellular-Network-Info" "183" research` | TS29.165 trust/interconnect tables; Telia secondary account; no direct new capability evidence. |
| `"VoLTE" "location tracking" "SIP" security research`; `"IMS" "Cellular-Network-Info" attack`; `"cell ID sequence" mobility inference stationary research` | SSTIC 2017 lead; O2 CVE; route classification and CT-Mapper leads. |
| `"Subscribers remote geolocation and tracking" VoLTE SSTIC 2017`; `site.sstic.org "VoLTE"`; `"Olivier Le Moal" "P-Access-Network-Info"`; `"VoLTE" "tracking" "Coudray"` | S1 original paper and references to 2015 precursor. |
| `"Energy-Efficient Positioning" "Cell-ID Sequence Matching" CAPS`; `"From Cells to Streets" Cell*`; `"cell" "closest antenna" "fallacy"`; `"Cellular-Network-Info" "mobility"` | S4/S5/S6; no match establishing CNI mobility inference. |
| `Paek CAPS cell ID pdf USC 2011`; `"CAPS" "cell-ID" "one second"`; `"cell-ID sequence" "remote" "SIP"`; `"CAPS" "Paek" "cell-ID" USC pdf`; `"Paek" "2000024" pdf` | Original CAPS indexed paper/slides; no exact SIP sequence study located; absence is not novelty proof. |
| `"cell ID" "transport mode" "signal strength" inference research`; `"cell tower" "mobility mode" "classification" sequence`; `"cell-id sequence" "stationary" "moving" study`; `"MonoSense" pdf`; `"Parsimonious mobility classification" pdf`; `"The Diversity and Scale Matter" pdf` | S7 and 2008 mobility paper; clarify RSSI/Wi-Fi feature mismatch. |
| `"Cellular-Network-Info" "freshness"`; `"Cellular-Network-Info" "accuracy"`; `"VoWiFi" "mobility inference"`; `"IMS" "cell-info-age" research` | O2 and patent; no systematic CNI freshness evaluation located. |
| `site.etsi.org "20 bits" "28 bits" "eNB ID" "23.003"`; `site.etsi.org "short macro eNB ID" "18"` | S8 identifies 20-bit prefix grouping limitation. |
| `site.insinuator.net 2016 "VoWiFi"`; `"IMSecure" "VoWiFi" IMEI`; `"VoWiFi" "RTT" "location" inference paper` | Original ERNW and DeepSec 2017 security work; additional metadata leaks already reported; no new focused RTT capability here. |
| `site.etsi.org "36.305" "E-CID" "RSRP"`; `site.etsi.org "36.305" "Cell-ID positioning"` | S9 confirms need additional radio measurements. |
| `"Paek11b.pdf"`; `"cell-id sequence" "CAPS" pdf -site:researchgate.net`; `"Paek" "CAPS" "2011" "pdf" "USC"`; `"Energy-Efficient Positioning for Smartphones" filetype:pdf -site:cseweb.ucsd.edu -site:sigmobile.org`; `site:nsl.cau.ac.kr "caps" "2011"` | USC original-author PDF link and ACM download failed; the academic UCSD mirror was retrieved through a public download and complete original read. |
| `"Security Threats and Countermeasure Frame" IEEE pdf`; `"Security Threats and Countermeasure Frame Using a Session Control Mechanism on VoLTE" "10.1109"`; `"10.1109/BWCCA.2015.11" "document"`; `"Security Threats and Countermeasure Frame" pdf -site:researchgate.net -site:scribd.com`; `"7424882" "VoLTE"`; `"Park" "Son" "BWCCA" "2015" "532" "pdf"` | Followed S1's Park citation to original IEEE 7424882 via DOI metadata; full original not accessible. Explicitly excluded unverified result details. |
| `"Hybrid GPS and GSM localization" "Deblauwe" pdf`; `"Hybrid GPS and GSM Localization" "spatial triggers"`; `"Hybrid GPS and GSM" filetype:pdf "Deblauwe" -site:researchgate.net`; `"Nico Deblauwe" "Georg Treu" "localization"`; `"4510373" "WPNC"`; `"Deblauwe" "spatial triggers" "pdf"`; `"Deblauwe" "Treu" site:ifi.lmu.de`; `"Hybrid GPS and GSM localization" site:vub.be`; `"Hybrid GPS and GSM localization" site:ieeexplore.ieee.org` | Followed CAPS §7; 2008 original unavailable, but earlier 2007 author original and publisher record located and read. |
| `"Combining GPS and GSM Cell-ID" pdf -site:researchgate.net`; `"Treu" "Hybrid" "localization" "ifi.lmu.de"` | S10 author original+academic mirror+publisher record; proactive zone inference and reporting tradeoffs predate this project. |
| `"Cellular-Network-Info" departure detection probe budget 2026`; `"Cellular-Network-Info" "departure"`; `"Cellular-Network-Info" "tracking" "probe"`; `"Cellular-Network-Info" "geofence"`; `"Cellular-Network-Info" geofencing OR departure OR event detection OR sampling OR probe budget`; `"Cellular-Network-Info" "departure" "2026"`; `"VoWiFi" "departure" "location" SIP`; `"VoLTE" "location" "departure detection"` | No directly matching remote CNI departure/event/probe-budget evaluation located. Standards/trust stripping, patents and unrelated sources dominate results. Broader prior art in S1/S4/S5/S10 still blocks broad novelty claims. |

### 9.2 Caller signaling, state and timing

1. `VoWiFi IMS SIP timing side channel infer smartphone state screen idle research`
2. `VoLTE SIP caller remote device fingerprint battery screen state research`
3. `IMS SIP privacy caller metadata P-Access-Network-Info target research`
4. `"VoLTE" "fingerprinting" paper`
5. `"SIP" "screen" "side channel" research`
6. `"Freaky Leaky SMS" paper`
7. `"phone" "call setup time" "location" research privacy`
8. `"SIP" "device fingerprinting" paper remote`
9. `"VoLTE" "privacy" "callee"`
10. `"VoWiFi" "callee" "location"`
11. `"When Good Turns Evil" paper pdf`
12. `"Insecurity of Voice Solution VoLTE" pdf`
13. `"VoLTE" "side-channel" "callee" privacy screen`
14. `"VoWiFi" privacy paper metadata phone state`
15. `"SIP" "temporal fingerprinting" Luxembourg pdf`
16. `"SIP" "idle" "timing" "privacy" phone research`
17. `"VoLTE" "screen" "privacy" research`
18. `"Careless Whisper" "receipts" paper`
19. `"smartphone" "power saving" "remote" "side channel" calls`
20. `"PTF: Passive Temporal Fingerprinting" pdf`
21. `"Semi-Supervised Fingerprinting of Protocol Messages" pdf`
22. `"SIP" "fingerprinting" "luxembourg" paper`
23. `"VoLTE" "call setup" "DRX" research measurement`
24. `"PTF" "François" "pdf" -site:researchgate.net -site:scribd.com`
25. `"PTF: Passive Temporal Fingerprinting" "doi"`
26. `"Semi-supervised Fingerprinting of Protocol Messages" "doi"`
27. `Telia SIP cell location disclosure P-Access-Network-Info 2026`
28. `"Cellular-Network-Info" "privacy" research`
29. `"cell-info-age" "183"`
30. `"P-Access-Network-Info" "callee" "privacy"`
31. `"Performance characterization and call reliability diagnosis" pdf`
32. `"Control-Plane Protocol Interactions in Cellular Networks" pdf`
33. `"Ghost calls from operational 4g call systems" pdf`
34. `"Wi Not Calling" pdf`
35. `"The Dark Side of Operational Wi-Fi Calling Services" pdf`
36. `"VoWiFi" "privacy" "183" research`
37. `"The Dark Side of Operational Wi-Fi Calling Services" threat model`
38. `"Wi Not Calling" "adversary"`
39. `"SIP" "screen state" "timing"`

This lane located the Ghost Calls caller-timing study, messenger/SMS timing work, and radio/tunnel studies with different access. It did not locate a directly verified IMS physical-screen-state evaluation. Generic SIP fingerprinting and the inaccessible Wi Not Calling lead remain scoped as described in §8.

### 9.3 Lead review: counterexamples, closest-work follow-up and version check

1. `"Cellular-Network-Info" privacy location tracking research`
2. `VoWiFi SIP location tracking cell ID caller paper`
3. `VoLTE silent call side channel screen power state inference paper`
4. `IMS SIP response timing target activity location inference research`
5. `"Wi-Fi Calling" "location" "privacy" paper IMS`
6. `"VoWiFi" "183" "location"`
7. `"Cellular-Network-Info" "attack"`
8. `"VoLTE" "tracking" "SIP" security paper`
9. `IMS SIP "screen" "timing" inference smartphone`
10. `VoWiFi "idle" "183" latency`
11. `SIP call setup timing side channel user state phone idle screen`
12. `"cell id sequence" "departure" mobility location stationary`
13. `"cellular-network-info" "freshness" tracking`
14. `"IMS" "screen on" "side channel"`
15. `"VoWiFi" "timing" "privacy" screen`
16. `Freaky Leaky SMS USENIX Security 2023 delivery reports PDF`
17. `"SIP" "timing" "location inference" phone`
18. `"VoLTE" "side channel" "screen"`
19. `"caller" "IMS" "reachability" "privacy" inference`
20. `"cell-info-age" "airplane" "WiFi"`
21. `"VoLTE" "screen" "power saving" call setup delay`
22. `"Ghost Calls from Operational 4G Call Systems" inference follow up`
23. `"Ghost Calls" "screen" IMS`
24. `"Ghost Calls" "location" VoWiFi timing`
25. `"IMS" "interactive" "timing" phone privacy`
26. `"SIP" "screen state" inference`
27. `"caller" "screen" "VoWiFi"`
28. `"Cellular-Network-Info" "departure"`
29. `site.etsi.org "124 229" "2026" "V19.7.0"`
30. `site.etsi.org "124 229" "2026" "V19.8.0"`
31. `"Hope of Delivery" NDSS 2023 paper`

**Outcomes of the additional checks.** Ghost Calls was read as the closest caller-side state prior, not treated as proof of screen-state inference. Messenger and SMS studies supplied path/training/intervention counterexamples. Hope of Delivery was followed and read as a separate known-location timing mechanism. Searches for departure/freshness did not locate a directly matching CNI event-budget evaluation, while CAPS, Cell* and spatial-event originals establish substantial broader prior art.

**Version check beyond keyword search.** Search indexing did not surface the newest PDF reliably. The [official ETSI TS 24.229 directory](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/) and its [19.07.00 directory](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.07.00_60/) were opened. The version front page confirms August 2026; the cited CNI, error-origin and recent-camping sections were read in V19.7.0. This check supersedes the reviewers' earlier V19.6.0 lookup for the citations in this review.

**Search limit.** No matching paper is not proof of novelty. Search terminology differs across IMS, SIP, mobility and location-service work, and some originals remain inaccessible. If the pilot yields a concrete effect, repeat the closest-work search using that effect and its actual access assumptions before making a novelty claim.

## 10. Discovery completion record

| Agreed requirement | Delivered evidence |
|---|---|
| Inspect both projects and existing paired captures | §2.1–2.2: versions, integrity, incomplete recordings, pairing and independent units |
| Audit actual fields, freshness, registered status and alignment | §2.3–2.5 and §6.1: populated fields, cached radio, distinct clocks and chronology limits |
| Preserve previous negative findings | §2.6: failed replication, unsupported extra fields, timing/movement limits and calibration failure |
| Generate a bounded set and deeply examine three | §3–5: fourteen original-study/disclosure entries, issuing standards, twelve questions and three detailed finalist reviews |
| Independently challenge and resolve objections | §6 plus independent reassessment: denominator, causal-time, logger/probe, prior-work, resource, unseen-place and access-loss objections incorporated |
| Qualify recommendations or report none | §7: A withdrawn as preferred after the user's challenge; B remains unverified; no preferred capability direction currently qualifies. C rejected as standalone |
| Supply references and exact dated searches | Linked primary references and access limits in §3/§8; exact queries and version verification in §9 |
| Stop at reviewed finding | Discovery review complete and recommendation revised. No new field collection, application change or deployment performed |

The independent consistency check confirmed the core denominators and saved-clock chronology: 338 total / 305 paired / 276 seven-block attempts; 212 seven-block cell observations / 209 SIP primary transactions; 301 response-time joins = 138 later + 163 earlier callbacks; 81 nominally fresh comparisons = 3 later + 78 earlier inferred modem measurement times. A subsequent independent reassessment agreed with withdrawing A after the user's application/availability objections. The audit and literature work remain valid; the initial preferred-direction qualification is superseded by §1/§6.2/§7.
