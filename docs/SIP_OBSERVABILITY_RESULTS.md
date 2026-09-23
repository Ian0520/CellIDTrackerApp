# SIP Observability Experiment Results

## Research Question

Can caller-observed INVITE-to-provisional-response latency or additional SIP
fields reliably distinguish the target's Wi-Fi network condition beyond the
existing Cell ID observation?

## Method

Two stationary-target A-B-A campaigns compared university Wi-Fi (A) with a
phone hotspot (B). Each primary block used 30 completed INVITE transactions,
a 10-second minimum interval, and one matching provisional response per probe.
Caller captures were joined to target ground truth using Unix timestamps, while
all latency durations came from the caller's monotonic clock.

On Day 2, a nominal B block was accidentally recorded without changing the
target network. It was retained as an additional unchanged-A control instead of
being relabeled or discarded.

## Aggregate Results

| Day | Block | Condition | n | Median latency |
|---|---|---|---:|---:|
| 1 | A1 | University Wi-Fi | 30 | 754.5 ms |
| 1 | B | Phone hotspot | 30 | 1265.5 ms |
| 1 | A2 | University Wi-Fi | 30 | 759.5 ms |
| 2 | A1 | University Wi-Fi | 30 | 678.0 ms |
| 2 | A-extra | University Wi-Fi unchanged | 29 | 847.0 ms |
| 2 | B | Phone hotspot | 30 | 823.0 ms |
| 2 | A2 | University Wi-Fi | 30 | 761.0 ms |

Day 1 showed a 508.5 ms hotspot-minus-pooled-A median increase. The comparable
Day 2 increase was 97.5 ms, or 94.0 ms when the unchanged-A control was included.
Corrected Day 2 B was 24 ms lower than the immediately preceding unchanged-A
control; the two distributions were not distinguishable by the exploratory
permutation test (`p = 0.868`).

Day 1 A-B-A patterns in Contact/SDP origin, Record-Route, SDP connection pools,
and response-source fields did not replicate on Day 2. These fields also changed
between two Day 2 sessions where the target remained on network A. No additional
caller-visible feature survived the complete replication rule.

## Conclusion

The tested hypothesis was not supported. Network and IMS-path conditions can
change probe latency, but neither latency nor the additional SIP fields reliably
identified university Wi-Fi versus hotspot under these controls. The observations
must not be interpreted as evidence of physical-location inference.

This is a useful negative result: natural signaling-path variation can imitate
an apparent target-network effect. Future work should require unchanged-condition
controls and independent replication before treating a timing or header pattern
as a research candidate.

## Reproduction

Raw captures are intentionally excluded from Git. With authorized captures in
the ignored `captures/` directory, run:

```bash
python3 analysis/sip_observability.py \
  --config analysis/day1_aba_20260922.json \
  --captures-root captures \
  --output captures/day1_aba_analysis

python3 analysis/sip_observability.py \
  --config analysis/day2_aba_20260923.json \
  --captures-root captures \
  --output captures/day2_aba_analysis

python3 analysis/compare_sip_observability_days.py \
  --config analysis/cross_day_aba_20260923.json \
  --captures-root captures \
  --output captures/cross_day_aba_analysis
```
