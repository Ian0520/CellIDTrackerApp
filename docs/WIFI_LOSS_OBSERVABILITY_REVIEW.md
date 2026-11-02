# Can caller signaling distinguish manual Wi-Fi off from leaving coverage?

Reviewed **2026-10-06 (Asia/Taipei)**. Follow-up to [RESEARCH_OPPORTUNITY_REVIEW.md](RESEARCH_OPPORTUNITY_REVIEW.md), especially §7.1. Literature and read-only instrumentation review; no new phone probes or application changes.

## 1. Answer and scope

**There are plausible transient differences to inspect, but this review found no verified caller-only method that reliably identifies these two actions.** Standards distinguish orderly tunnel release, silent failure detection and access transfer. They do not make those mechanisms synonymous with the user's actions:

- A: an authorized target stays still and turns the **Wi-Fi radio** off.
- B: the target walks beyond its current AP's coverage.

Either action can end in cellular IMS service, temporary unreachability, stale network state, or loss of the current CNI observation. A detected access change or failure is therefore insufficient evidence of physical departure. Turning off the Wi-Fi Calling service setting, disconnecting one SSID, and disabling the Wi-Fi radio must also be labelled separately.

The most useful question is narrower: **does this phone/carrier combination expose a repeatable difference in the caller's response sequence and timing around the two labelled transitions?** A positive result would describe its conditions and time window. It would not establish a general movement detector, unseen-place inference, or novelty.

The user reports that Cell ID disappears on the present observation path when target Wi-Fi is lost. Existing stationary data did not test that transition. This report retains it as an operational constraint, and asks what other caller observations might survive.

## 2. Verified evidence and its limits

| Source, version and date | Relevant material read | Evidence and observer boundary |
|---|---|---|
| [3GPP TS 24.302 V19.0.0, January 2026](https://www.etsi.org/deliver/etsi_ts/124300_124399/124302/19.00.00_60/ts_124302v190000p.pdf) | §§7.2.2A, 7.2.4.1, 7.2.4.3, 7.4.1A | UE–ePDG release and liveness procedures differ; some releases are local. These target/operator events are not direct caller notifications. Timing depends on configuration and policy. |
| [RFC 7296, October 2014](https://www.rfc-editor.org/rfc/rfc7296.html) | §§1.4.1, 2.4 | IKEv2 supports explicit SA deletion and failure detection through unanswered exchanges. It does not prescribe a universal loss-classification timeout. |
| [GSMA IR.51 V9.0, 31 May 2021](https://www.gsma.com/newsroom/wp-content/uploads/IR.51-v9.0.pdf) | §§4.7.5–4.8, Annex B.3 | Wi-Fi-off release depends on cellular availability and handover policy. Seamless LTE/Wi-Fi transfer is profiled. Liveness is configurable; the listed two-minute default is one UE parameter, not a caller error deadline. |
| [3GPP TS 24.229 V19.6.0, March 2026](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.06.00_60/ts_124229v190600p.pdf) | §§5.2.8.1.1–2, 5.2.14, 7.2A.18.10–12, R.2.2.1 | Some bearer failures can cause SIP diagnostics; access transfer may retain IP/P-CSCF. Network access-update messages are not automatically delivered to the remote caller. |
| [Ericsson Review, 30 January 2015](https://www.ericsson.com/4ac565/assets/local/news/2015/1/er-wifi-calling.pdf), *Wi-Fi calling – extending the reach of VoLTE to Wi-Fi* | pp2–5 | Architecture can preserve IP and make access transfer largely transparent to IMS. Architecture support does not prove deployment on our carrier. |
| [GSMA/WBA, *Enriched service over converged Wi-Fi and cellular network*, cover March 2025](https://www.gsma.com/solutions-and-impact/technologies/networks/wp-content/uploads/2025/03/GSMA-Voice-over-Wi-Fi-Whitepaper-March25.pdf) | §§6.5.1–6.5.3 | Suggested ongoing-call handover decisions include link quality, delay, packet loss and congestion. Congestion can cause transfer without motion. This is guidance, not a measurement of our SIP timing channel. |
| [Vivo EP4164307A1, published 12 April 2023](https://data.epo.org/publication-server/rest/v1.2/publication-dates/20230412/patents/EP4164307NWA1/document.pdf) | Background ¶0004–0005; manual-action definition ¶0022; proposed disconnect sequence | Describes stale tunnel/registration state after disconnect and proposes completing cleanup before Wi-Fi shutdown. It challenges the assumption that manual off is always orderly. A patent is a proposed design, not empirical proof of this handset's behavior. |
| [Lu et al., *Ghost Calls*, MobiCom 2020](https://xietian1.github.io/paper/Mobicom2020.pdf) | §5.1, pp8–9 | Caller headers, message flow and progress timing reveal some broad states. Their VoWiFi-ready and VoLTE-ready observations were not clearly separable. No manual-off versus walking-out evaluation is established. |
| [Baek et al., *Wi Not Calling*, ACSAC, 3–7 December 2018](https://sefcom.asu.edu/publications/wi_not_calling-acsac18.pdf) | §§5.2–5.3, Table 3 | Controlled link loss during ongoing calls produced different drop/transfer outcomes across tested settings. Same-WLAN access and active calls differ from our remote, early-cancel probes. Its mechanisms are counterevidence to a unique loss outcome, not a caller classifier. |
| [RFC 3261, June 2002](https://www.rfc-editor.org/rfc/rfc3261.html) | §§9, 21.4.9, 21.4.18, 21.4.25 | Timeout/unavailability responses have broader meanings; 487 can follow caller cancellation. These are not physical-movement labels. |
| [RFC 7315, July 2014](https://www.rfc-editor.org/rfc/rfc7315.html), updated by [RFC 9878, November 2025](https://www.rfc-editor.org/rfc/rfc9878.html) | RFC 7315 §4.4; RFC 9878 §§2.3.2, 3, 5 | PANI can appear in responses and convey access information, but trust/forwarding rules constrain delivery. Defined header support does not guarantee remote caller visibility. |

### What can plausibly differ?

**Orderly versus silent cleanup.** If the target releases its tunnel before losing access, the network may learn of the transition earlier than when it waits for liveness failure. A caller might see a different temporary delay/error/recovery pattern. This is an inference from the procedures, not a demonstrated caller effect. Manual off can also lose connectivity before cleanup; a coverage exit can transfer before loss. [TS 24.302](https://www.etsi.org/deliver/etsi_ts/124300_124399/124302/19.00.00_60/ts_124302v190000p.pdf), [Vivo patent](https://data.epo.org/publication-server/rest/v1.2/publication-dates/20230412/patents/EP4164307NWA1/document.pdf).

**Pre-loss degradation.** A gradual loss of target Wi-Fi quality might affect caller response timing or retransmissions before CNI disappears. Whether early progress actually depends on that link must be measured. Stationary congestion, obstruction and caller/network delay are competing explanations. Target RSSI is validation only; the caller's RSSI measures its own link. The white paper concerns voice quality and handover, so it does not establish this SIP-delay hypothesis. [GSMA/WBA 2025](https://www.gsma.com/solutions-and-impact/technologies/networks/wp-content/uploads/2025/03/GSMA-Voice-over-Wi-Fi-Whitepaper-March25.pdf), [Ghost Calls](https://xietian1.github.io/paper/Mobicom2020.pdf).

**Optional diagnostic reasons.** SIP `Reason` may describe bearer/QoS loss, signaling-bearer release, IKEv2 failure, or session-release events. Some early-setup errors can reach the caller; other diagnostics concern established sessions. “User ends call” is not a Wi-Fi-toggle flag. No verified reason identifies the two physical actions uniquely. [TS 24.229 §§5.2.8.1.1–2, 7.2A.18.10–12](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.06.00_60/ts_124229v190600p.pdf).

**A settled access state may be identical.** Preserved IMS connectivity can conceal the transfer; routing/header changes are not guaranteed. A disappearance of CNI can indicate loss of this observation channel without identifying the cause. Existing access-state measurement counterexamples strengthen this limit. [Ericsson 2015](https://www.ericsson.com/4ac565/assets/local/news/2015/1/er-wifi-calling.pdf), [Ghost Calls](https://xietian1.github.io/paper/Mobicom2020.pdf).

## 3. Caller information to inspect, in priority order

These are measurement hypotheses and completeness requirements, not established discriminators.

| Priority | Caller observation and packet source | Possible use | Main limitation |
|---|---|---|---|
| 1 | Complete **INVITE response sequence**: all matched 100/180/183 and final statuses, repeat flags, actual packet times | Determine whether loss changes progress, errors, or the response pattern | Network-generated progress need not prove target reachability. Match transaction and CSeq method; do not count cleanup responses as target events. |
| 1 | **Timing by stage**: actual INVITE send→100, send→first 180/183, 100→180/183; error arrival and observation duration | Look for pre-loss degradation or a temporary routing wait | Shared network delay, paging, target wakeup, routing and observation cutoff compete. No universal threshold follows from a standard timer. |
| 1 | **Across-probe loss/recovery history**: response categories and CNI presence before, during and after access loss | Identify when useful caller observation disappears and returns | With sparse probes, transitions are interval-censored. Recovery can support retrospective classification only, not a claim of earlier detection. |
| 2 | **CNI header population**, identity/RAT/reported age when parseable, and actual source response time | Compare the last/first observations around a transition | A cell can persist through movement or change at rest. No CNI, unsupported encoding and missing capture are different outcomes. |
| 2 | **`Reason` protocol/cause** in captured errors or release messages; optional error-origin information | Test whether an informative failure consequence is exposed | Audited earlier incoming INVITE responses contained zero `Reason`/`Response-Source`; availability in the required window is unverified. Most causes are not action-specific. |
| 2 | **PANI/access indicators**, when actually delivered | Check for surviving access/cell information after CNI loss | PANI was absent in audited earlier incoming INVITE responses. Caller forwarding is not guaranteed; the same access/cell can follow either action. |
| 3 | **Contact/Record-Route and SDP changes** | Diagnostic evidence of a path transition | Unchanged values do not exclude transfer; changed values do not establish motion. |

Always retain actual outgoing CANCEL/PRACK times and caller process/path context beside these observations. One selected response code and one exported `deltaMs` cannot substitute for the full matched sequence.

Do not assume that missing CNI proves all cell metadata is unavailable: inspect actual post-loss header population independently of the current cell parser. PANI may carry access/cell information, but it was absent in the previous captures and cannot be promised after fallback. [RFC 7315 §4.4](https://www.rfc-editor.org/rfc/rfc7315.html), [RFC 9878 §3](https://www.rfc-editor.org/rfc/rfc9878.html), [TS 24.229 §7.2A.4.3](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.06.00_60/ts_124229v190600p.pdf).

## 4. What current instrumentation can and cannot supply

Read-only source audit of both projects found:

- Native caller metadata already preserves captured status/header/time/transaction information before stale-response filtering. It is the appropriate starting point for a channel check. Individual transport payload recording is not a guarantee of complete TCP-reassembled SIP messages.
- The app's selected `sipStatus` and `deltaMs` primarily refer to the first handled 180/183. `no_provisional` does **not** mean no SIP response. A later CNI-bearing response can populate the cell while retaining the earlier progress timestamp; use the native source packet's time.
- Current one-progress settings send CANCEL early. Later failures can be censored, while 487 after our CANCEL is expected cleanup evidence. Native controller windows around 20/10 seconds are local cutoffs, not measured carrier liveness deadlines.
- The native configuration imposes a ten-second minimum between fresh INVITEs within a process. The old export's 11.261-second median is the interval between selected progress responses, **not a verified INVITE-to-INVITE cadence**. Actual sends and process runs must establish observation spacing. A short transient may fall between probes. No higher-rate probing or observation-policy change was performed here.
- Caller watchdogs/retries can cause process gaps during CNI loss. Separate actual target observations from caller restarts, missing captures and rollover.
- LocationData records target transport/Wi-Fi/radio snapshots during location callbacks. It has manual-event support, but the old nine paired exports have **no manual events** and no controlled Wi-Fi-off/coverage-exit contrast. Their three cellular snapshots do not establish such episodes.
- Target default transport is not target IMS access. There are no existing target IMS registration, IKE teardown, Wi-Fi-enabled/disconnect-cause or exact handover event records. Cached radio snapshots and uncertain cross-phone clock alignment also limit event timing.

Source anchors:

- [Caller capture and first-progress/cell handling](/home/ian/AndroidStudioProjects/CellIDTracker/wifi-calling/source/session.cpp:340), [capture serialization](/home/ian/AndroidStudioProjects/CellIDTracker/wifi-calling/source/sip_capture.cpp:337), [response parsing](/home/ian/AndroidStudioProjects/CellIDTracker/wifi-calling/source/sip_response.cpp:16).
- [Attempt reduction](/home/ian/AndroidStudioProjects/CellIDTracker/app/src/main/java/com/example/cellidtracker/probe/ProbeAttemptReducer.kt:76), [response interval tracker](/home/ian/AndroidStudioProjects/CellIDTracker/app/src/main/java/com/example/cellidtracker/probe/ProbeResponseIntervalTracker.kt:8), [export](/home/ian/AndroidStudioProjects/CellIDTracker/app/src/main/java/com/example/cellidtracker/experiment/ExperimentExport.kt:250), [controller](/home/ian/AndroidStudioProjects/CellIDTracker/wifi-calling/source/probe_controller.cpp:154), [process retries](/home/ian/AndroidStudioProjects/CellIDTracker/app/src/main/java/com/example/cellidtracker/probe/ProbeProcessCoordinator.kt:167).
- [Target callback collection](/home/ian/AndroidStudioProjects/LocationData/app/src/main/java/com/example/locationdata/GroundTruthViewModel.kt:218), [target telemetry](/home/ian/AndroidStudioProjects/LocationData/app/src/main/java/com/example/locationdata/TelemetrySnapshot.kt:84).

At minimum, classify each sent attempt separately as: parsed CNI; handled progress without parsed CNI; other matched INVITE responses; no matched response within the recorded window; or incomplete/censored observation. Report all attempted episodes, not successful responses alone.

## 5. Target information needed for validation

Target measurements are experimental truth and controls; they are excluded from any caller-only predictor.

| Target information | Purpose | Present availability |
|---|---|---|
| Assigned action, position, walking start, coverage exit/return and radio-toggle labels | Distinguish the action from its network consequence | Manual labels and position exist; previously saved sessions lack event labels. Position needs accuracy checks. |
| Wi-Fi association/loss/reconnect, enabled state and target RSSI/link history | Validate actual access transition and gradual versus sudden degradation | Snapshot RSSI/link/hash exist; exact association events and radio-enabled state are not exported. Residual Wi-Fi values alone do not certify association. |
| Cellular availability, WFC preference, screen/logger/power state | Match competing fallback and power conditions | Some context exists; verify actual voice access and policy. Default data transport cannot certify IMS handover. |
| Explicitly registered serving cell and modem measurement age | Control cellular-path context and stale snapshots | Available where populated, but often cached; filter registered cells explicitly. |
| IMS registration, IKE Delete/liveness and access-transfer events | Attribute the protocol mechanism independently | Not currently recorded. Use only if genuinely accessible; do not assume privileged phone/operator logs. |
| Cross-phone clock error before/after collection | Bound event/probe alignment | Not established in old exports. Carry uncertainty intervals. |

A label such as “walking out” cannot be derived from missing CNI, because that would build the desired conclusion into the truth data.

## 6. Next step supported by this review

**Inspect channel feasibility before building an AI classifier.** No capability direction is newly qualified by this literature review.

If a later pilot is agreed, start with repeated, counterbalanced episodes on the existing equipment: stationary unchanged access; stationary radio off/on; walking while still covered; walking beyond coverage and returning. Hold caller context and ordinary probe policy fixed, match loss/recovery windows, and label actions separately from observed access. An owned-AP outage is a useful nonmovement counterexample if needed; it is not part of this review's executed work.

First report which caller fields actually survive each phase, their observation windows and capture completeness. Then ask whether any separation persists across independent episodes and another day. Freeze a simple rule before evaluating held-out episodes; compare against CNI-presence/status-only baselines and allow unknown outcomes. Do not train on AP/session identifiers or silently feed target RSSI into the model.

Revise or abandon this contrast if:

1. Both actions produce overlapping caller histories with no useful independent separation.
2. Apparent separation comes from caller CANCEL, watchdogs, capture gaps or different cellular/power conditions.
3. The informative field exists only in target/operator logs or an answered call, beyond the current probe's scope.
4. The effect separates orderly/silent mechanisms but fails on the original action labels or a stationary access-loss control.
5. The transient is too brief for the current observation cadence, or classification needs recovery when the intended application needs an immediate alert.

Even a repeatable device-specific result needs another AP/day and eventually another phone/access-policy condition before a broader claim. AI can test combinations of informative observations; it cannot recover a physical cause from identical observed histories.

## 7. Verification and search record

The team used three parallel lanes: caller/IMS literature, access-metadata counterexamples, and actual instrumentation feasibility. The instrumentation reviewer separately challenged the synthesis and gave bounded acceptance after two corrections: the 11.261-second statistic describes selected responses rather than INVITE sends, and zero diagnostic headers is restricted to audited incoming INVITE responses. Both corrections are applied above; the response-interval correction is also applied to the discovery report. The reviewer retained the missing exact target-event truth and conditional inference boundaries. This is a targeted primary-source review, not an exhaustive systematic review or proof of impossibility.

TS 24.229 V19.7.0 was identified in the earlier discovery review, which verified its CNI sections. This follow-up's **new release-cause clauses were read in V19.6.0**; current V19.7.0 retrieval failures are not treated as semantic verification. IR.51 V9.0 is the explicitly checked edition, not a claim of the latest available edition.

Direct web retrieval of some GSMA/EPO PDFs failed. Complete original-domain IR.51, the 2025 white paper and EPO publication were then retrieved into temporary local files and their relevant text read. The original ACSAC 2018 paper was obtained in this follow-up; its earlier inaccessible status in the discovery report remains historical. Patents are used only to challenge implementation assumptions, not establish deployed capability. Generic forums, secondary summaries and unvalidated bug reports were excluded from substantive claims.

Retrieval date for all following searches: **2026-10-06**. Search results supplied leads; substantive claims use the linked primary documents. Lack of an exact match does not establish novelty.

### Root search queries

1. `VoWiFi WiFi turned off out of coverage IMS deregistration caller SIP delay handover study`
2. `Wi-Fi calling Wi-Fi loss abrupt graceful IMS deregistration SIP terminating call reachability`
3. `site.etsi.org "untrusted" "IP-CAN" "loss" "REGISTER" "24.229"`
4. `Wi-Fi calling handover cellular call setup latency WiFi disconnect measurement paper`
5. `site.gsma.com "IR.51" "loss" "WLAN"`
6. `site.etsi.org "24.302" "loss of" "WLAN"`
7. `site.android.googlesource.com IwlanDataService "network lost" "deactivate"`
8. `"VoWiFi" "Wi-Fi off" "handover" paper`
9. `"Wi-Fi control method" "VoWiFi deregistration"`
10. `"Wi-Fi calling" "manual" "deregistration" paper`
11. `"VoWiFi" "graceful" "disconnect"`
12. `"Wi-Fi calling" "abrupt" "caller"`
13. `"Wi-Fi" "manual" "off" "SIP" "183"`
14. `"Wi-Fi" "coverage" "caller" "IMS" "timing"`
15. `"VoWiFi" "disconnect" "fingerprint"`
16. `"VoWiFi" "connection loss" "classification"`

### Caller-literature search queries

1. `3GPP Wi-Fi calling WLAN switched off loss coverage deregistration IMS caller`
2. `3GPP TS 24.229 non-cellular IP-CAN loss connectivity de-registration WLAN`
3. `VoWiFi Wi-Fi disabled out of coverage handover call setup timing research`
4. `IMS WiFi calling graceful disconnect abrupt loss SIP reachability remote caller`
5. `site:etsi.org TS 24.302 WLAN connectivity loss ePDG detach`
6. `site:gsma.com IR.51 "loss" "Wi-Fi"`
7. `"Wi-Fi calling" "deregistration" "user" "disabled"`
8. `"Wi-Fi calling" "handover" "remote" "signaling" research`
9. `site:gsma.com "IR.51" "2.4.2.1"`
10. `site:gsma.com "IR.51" "Wi-Fi is switched off"`
11. `"WLAN" "out of coverage" "REGISTER" "IMS"`
12. `site:gsma.com "IR.51" "V10.0"`
13. `site:gsma.com "IR.51" "V11.0"`
14. `site:gsma.com "TS.22" "Wi-Fi" "user" "disable"`
15. `"IMS" "WLAN" "not reachable for SIP signalling"`
16. `site:gsma.com "TSG22" "Wi-Fi Calling" "disabled"`
17. `site:gsma.com "TS.22" "Wi-Fi is" "switched"`
18. `site:etsi.org "TS 24.229" "not reachable for SIP"`
19. `site:gsma.com "IR.51" "User-Initiated De-Registration"`
20. `site:gsma.com "IR.51" "Loss of Radio Connection" "10.0"`
21. `"SIP" "graceful" "abrupt" "Wi-Fi" calling study`
22. `"Wi-Fi off" "call setup" "SIP"`
23. `"VoWiFi" "Wi-Fi" "switch off" paper`
24. `"VoWiFi" "loss" "coverage" measurement paper handover`
25. `"Wi-Fi calling" "abrupt"`
26. `"IMS" "graceful" "deregistration" "caller"`
27. `site:gsma.com "IR.51-v10.0-1.pdf" "de-registration"`
28. `site:gsma.com "IR.51-v10.0-1.pdf" "Loss of Radio"`
29. `site:gsma.com "TS.22-v6.0.pdf" "TSG22_R6_SVC_31"`
30. `site:etsi.org "Reason header" "loss of radio"`
31. `site:android.googlesource.com "CODE_WIFI_LOST"`
32. `site:android.googlesource.com "CODE_EPDG_TUNNEL_LOST"`
33. `"VoWiFi" "disconnect" "privacy" "timing"`
34. `"TS 29.274" "8.103" "IKEv2"`
35. `"TS 29.274" "Cause for protocol failure" "WLAN"`
36. `"IKEV2" "Reason" "SIP" "wifi"`
37. `"Reason" "WLAN" "user" "deregistration" SIP`
38. `site:gsma.com "TS.22" "TSG22_R6_SVC_24"`
39. `"Wi-Fi Calling" "handover" "signal strength" "manual" paper`
40. `"VoWiFi" "user initiated" "loss" "remote"`
41. `"caller" "Wi-Fi" "disconnect cause" "SIP"`
42. `"GSMA" "Enriched Service" "Wi-Fi Calling" white paper`
43. `"Wi-Fi calling" "manual" "switch off" handover measurement study`
44. `"Enriched service over converged" "pdf" -site:scribd.com -site:linkedin.com`
45. `"IMS" "WLAN" "Wi-Fi off" caller inference research`
46. `"Wi-Fi calling" "deregistration" "DPD" patent`

### Metadata/counterevidence search queries

1. `"VoWiFi" "Wi-Fi" "handover" "SIP" "P-Access-Network-Info" measurement`
2. `"Ghost Calls" "Wi-Fi" Lu 2020 metadata`
3. `"VoWiFi" "WiFi" "disable" "handover" SIP measurement study`
4. `"VoWiFi" "disconnect" "Reason" SIP`
5. `"Wi-Fi calling" "walking" "handover" research measurement`
6. `"P-Access-Network-Info" "responses" "terminating" "24.229"`
7. `site:etsi.org "23.402" "18.3.0" pdf`
8. `site:etsi.org "24.302" "IPsec tunnel" "Delete"`
9. `site:etsi.org/deliver "124302v" "2025" "Release 19"`
10. `"VoWiFi" "manual" "signal" "handover" measurement`
11. `"WiFi calling" "turn off" "SIP" measurement research`
12. `"Wi-Fi calling" "out of range" "signaling" research`
13. `site:etsi.org "TS 124 302" "V19"`
14. `site:etsi.org "TS 124 302" "Delete Payload"`
15. `"Wi-Fi" "loss" "Response-Source" IMS`
16. `"Wi-Fi" "handover" "Warning" SIP caller`
17. `"24.302" "2026" "V19"`
18. `"24.229" "19.7.0" etsi`
19. `"VoWiFi" "loss" "caller" "timing" research`
20. `"Wi-Fi calling" "manual" "stationary" inference`
21. `"Cellular-Network-Info" "Wi-Fi" "disconnect"`
22. `"P-Access-Network-Info" "Wi-Fi" "movement"`
23. `"VoWiFi" "caller" "handover" "inference"`
24. `"Wi-Fi calling" "out of coverage" "SIP" "measurement"`
25. `site:gsma.com "IR.51" "4.7.5" "Wi-Fi connection is turned off"`
26. `site:gsma.com "IR.51" "2025" pdf`

Local sanitized working memos are `/tmp/wifi_loss_measurement_audit.md`, `/tmp/wifi_loss_caller_literature.md`, and `/tmp/wifi_loss_metadata_review.md`. Temporary files are not permanent archival guarantees; this report retains their substantive findings and source locators. No private identifiers or captures were submitted to external search services.
