# Caller-visible cell sequence study

## Research question

The caller receives at most one structured cell observation per fresh INVITE
attempt. This study tests whether the **sequence** of those observations adds
useful information beyond the latest cell, and whether a calibrated area for a
single cell improves location estimates. All devices and networks must be owned
or explicitly authorized for this research.

`Cellular-Network-Info` may identify a cell the target *most recently camped*
on, rather than its currently registered cell (3GPP TS 24.229, §7.2.15). An LTE
ECI encodes an eNodeB identifier and a cell identifier. Grouping by eNodeB is a
useful coarser observation, but it is not a measured physical tower position.

## Existing stationary control

The seven stationary blocks from the two-day SIP observability campaign contain
276 structured attempts. A cell was visible in 212; the app's numeric identity
agreed with the captured header in all 212. The raw cell changed frequently while
the target was stationary. A rule requiring three consecutive observations of a
new cell produced five alerts across these blocks. Applying the same rule to the
LTE eNodeB group produced zero alerts. All captured first provisional responses
were `183`, and all parsed cell headers described LTE. These blocks do not test
call-state inference. This is a retrospective control, not a validation of
movement detection.

Only 76 attempts had both a raw cell and a registered target modem observation
aged at most 5 seconds. Exact cell identity agreed in 45; eNodeB group agreed in
73. At a 30-second modem age cutoff, exact identity agreed in 134 of 212 and
eNodeB group in 201 of 212. The broader window admits older target readings and
is a sensitivity analysis, not a replacement for the fresh comparison.

All 212 historical geolocation lookups for these blocks failed with HTTP 404.
Consequently, these captures cannot establish the location error of the old
Google request. The request has since been corrected in source to put `radioType`
at the request level; its live outcome still needs a controlled check.

A day-separated stationary-only benchmark exercises the offline scoring code:
94 of 97 eligible Day 2 observations received an empirical cell estimate from
Day 1 training, with 52.5 m median distance error and 0 of 94 within the
training-derived 90% radius. No Google estimates were available. Repeated probes
at these sites do not establish accuracy across routes; the zero containment is
a reason to test calibration on the planned route before considering an app.

## Prospective question and fixed rule

Primary question: does a new LTE eNodeB group observed in **three consecutive
attempts carrying that group** provide a useful area-change alert during an authorized
moving block while retaining a low false-alert rate in stationary controls?

Record alert count per stationary block, moving blocks with at least one alert,
time from the operator's movement-start label to first alert, and header
availability in every block. Report the raw-cell version beside the eNodeB rule
as a comparison. A missed alert is possible if the route stays within one eNodeB
group; such a block remains a valid negative result.

Secondary question: for a fresh cell, what area contains the target? Compare the
existing single-cell Google result, when available, against an empirical cell
area trained on one day's paired GPS data and scored on another day. Report
median and 90th-percentile distance error, 90% area containment, coverage of
previously seen cells, and abstentions for unseen cells. Do not pick a method by
the API's *reported* accuracy radius alone. Do not pool cells from different
probe times as if they were simultaneous tower measurements.

## Collection procedure

1. Select a safe, repeatable route using target-only radio telemetry. Prefer a
   route that crosses at least two LTE eNodeB groups while maintaining one
   authorized portable Wi-Fi hotspot. Freeze the route before scored collection.
2. Keep the APK, native probe, target app, hotspot, SIM/carrier, and analysis rules
   fixed for both days. Use a 10-second minimum interval, one provisional
   response per attempt, and controlled auto-restart. Record app/native hashes.
3. On each day, collect three separate caller/target session pairs with matching
   campaign and condition labels: **S1** stationary at the route start for 30
   fresh attempts; **M** 60 attempts that begin at the same point, cover the
   fixed moving route, and continue after the target returns; **S2** stationary
   at the same start point for 30 attempts. In M, wait for three consecutive
   attempts carrying the same eNodeB group at the start before moving, and keep
   probing after movement ends.
   Record movement start/end as manual target events. Use target location
   sampling at about 1 Hz and retain modem measurement age from `LocationData`.
4. Repeat S1–M–S2 on a second day. Keep interrupted blocks as diagnostics and
   repeat them under a new label. Do not combine their attempts with a valid
   block. Record IMS loss, hotspot reconnects, process restarts, or other
   changes in each session's notes.
5. Export caller and target sessions after every block. Keep raw captures,
   coordinates, phone numbers, Call-IDs, and API keys under ignored private
   storage. Commit only aggregate reports.

The moving target and portable hotspot must remain together. Do not infer
physical movement from a cell change alone; compare alerts with the operator's
phase and target GPS record. The target telephony API may return cached cell
measurements, so preserve its `ageMillis` and report freshness exclusions.

## Analysis commands

The existing stationary control can be reproduced without a phone:

```bash
python3 analysis/cell_sequence.py \
  --config analysis/cross_day_aba_20260923.json \
  --captures-root captures \
  --output captures/cell_sequence_analysis

python3 analysis/cell_sequence.py \
  --config analysis/cross_day_aba_20260923.json \
  --captures-root captures \
  --output captures/cell_sequence_analysis_30s \
  --max-ground-cell-age-ms 30000
```

For the new route, create a private config with `maxGroundTruthGapMs`,
`maxGroundCellAgeMs`, and a `sessions` array. Each session entry needs `day`,
`label`, `phase` (`stationary` or `moving`), `campaignId`, `conditionLabel`,
`callerSessionId`, and `groundTruthFile`; use `groundTruthConditionLabel` only
when the target's recorded label differs. For a moving entry, also copy the
manual event times into private `movementStartMillis` and `movementEndMillis`
fields. Both times must fall inside the probe sequence. The analyzer writes aggregate
`report.md` and `summary.json` under the ignored output directory. The
geographic benchmark should use explicit day-separated training and test
sessions, never a random split of probes from the same route.

## Interpretation boundaries

This study is specific to the authorized device, operator, IMS path, and sites.
It can support an area-change application only if both moving-block detection
and stationary-block false-alert rates remain useful on the second day. Better
location precision requires held-out GPS evidence; repeated identical cell IDs
alone add no geometric measurement.

Standards and API references:

- [3GPP TS 24.229, Cellular-Network-Info](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.03.00_60/ts_124229v190300p.pdf)
- [3GPP TS 36.305, Cell ID and enhanced Cell ID positioning](https://www.etsi.org/deliver/etsi_ts/136300_136399/136305/16.05.00_60/ts_136305v160500p.pdf)
- [Google Geolocation API request and response](https://developers.google.com/maps/documentation/geolocation/requests-geolocation)
