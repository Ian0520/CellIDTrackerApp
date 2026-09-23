#!/usr/bin/env python3
"""Compare replicated A-B-A SIP observations without exporting raw identifiers."""

from __future__ import annotations

import argparse
import csv
import json
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

if __package__:
    from .sip_observability import (
        BLOCK_COLORS,
        BlockData,
        BlockSpec,
        candidate_feature_sets,
        cliffs_delta,
        configure_plot_style,
        enrich_transactions,
        load_block,
        markdown_table,
        numeric,
        parse_config,
        percentile,
        permutation_median_p_value,
        save_figure,
    )
else:
    from sip_observability import (
        BLOCK_COLORS,
        BlockData,
        BlockSpec,
        candidate_feature_sets,
        cliffs_delta,
        configure_plot_style,
        enrich_transactions,
        load_block,
        markdown_table,
        numeric,
        parse_config,
        percentile,
        permutation_median_p_value,
        save_figure,
    )


@dataclass
class DayData:
    label: str
    blocks: dict[str, BlockData]
    features: dict[str, dict[str, set[str]]]


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def write_csv(path: Path, rows: Sequence[dict[str, Any]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def load_day(spec: dict[str, Any], captures_root: Path) -> DayData:
    config, block_specs = parse_config(Path(spec["config"]))
    blocks = [
        load_block(
            block_spec,
            captures_root,
            config["campaignId"],
            int(config["primaryAttemptsPerBlock"]),
            int(config["maxGroundTruthGapMs"]),
        )
        for block_spec in block_specs
    ]
    enrich_transactions(blocks)
    return DayData(
        label=spec["label"],
        blocks={block.spec.label: block for block in blocks},
        features=candidate_feature_sets(blocks),
    )


def load_control(spec: dict[str, Any], captures_root: Path) -> BlockData:
    block_spec = BlockSpec(
        label=spec["label"],
        condition_label=spec["conditionLabel"],
        ground_truth_condition_label=spec.get(
            "groundTruthConditionLabel", spec["conditionLabel"]
        ),
        caller_session_id=spec["callerSessionId"],
        ground_truth_file=spec["groundTruthFile"],
        network_name=spec["networkName"],
        primary_wifi_frequency_mhz=int(spec["primaryWifiFrequencyMhz"]),
    )
    block = load_block(
        block_spec,
        captures_root,
        spec["campaignId"],
        int(spec["primaryAttempts"]),
        int(spec["maxGroundTruthGapMs"]),
    )
    enrich_transactions([block])
    return block


def latency_values(block: BlockData) -> list[float]:
    return numeric(observation["attempt"].get("deltaMs") for observation in block.primary)


def summarize_block(day: str, block: BlockData) -> dict[str, Any]:
    values = latency_values(block)
    return {
        "day": day,
        "block": block.spec.label,
        "network": block.spec.network_name,
        "n": len(values),
        "median_ms": statistics.median(values),
        "q1_ms": percentile(values, 0.25),
        "q3_ms": percentile(values, 0.75),
        "mean_ms": statistics.fmean(values),
        "sd_ms": statistics.stdev(values) if len(values) > 1 else 0.0,
    }


def bootstrap_network_effect(
    b_values: Sequence[float],
    a_groups: Sequence[Sequence[float]],
    rng: np.random.Generator,
    iterations: int,
) -> tuple[float, float, float, np.ndarray]:
    observed = statistics.median(b_values) - statistics.median(
        [value for group in a_groups for value in group]
    )
    b_array = np.asarray(b_values, dtype=float)
    a_arrays = [np.asarray(group, dtype=float) for group in a_groups]
    samples = np.empty(iterations, dtype=float)
    for index in range(iterations):
        sampled_b = rng.choice(b_array, len(b_array), replace=True)
        sampled_a = np.concatenate(
            [rng.choice(group, len(group), replace=True) for group in a_arrays]
        )
        samples[index] = np.median(sampled_b) - np.median(sampled_a)
    low, high = np.quantile(samples, [0.025, 0.975])
    return observed, float(low), float(high), samples


def jaccard(first: set[str], second: set[str]) -> float:
    union = first | second
    return len(first & second) / len(union) if union else 1.0


def aba_return(features: dict[str, set[str]]) -> bool:
    return bool(
        features["A1"]
        and features["A1"] == features["A2"]
        and features["A1"] != features["B"]
    )


def plot_latency(
    day1: DayData,
    day2: DayData,
    control: BlockData,
    output_dir: Path,
    seed: int,
) -> None:
    groups = [
        ("D1 A1", day1.blocks["A1"], BLOCK_COLORS["A1"]),
        ("D1 B", day1.blocks["B"], BLOCK_COLORS["B"]),
        ("D1 A2", day1.blocks["A2"], BLOCK_COLORS["A2"]),
        ("D2 A1", day2.blocks["A1"], BLOCK_COLORS["A1"]),
        ("D2 A-extra", control, "#2F7F83"),
        ("D2 B", day2.blocks["B"], BLOCK_COLORS["B"]),
        ("D2 A2", day2.blocks["A2"], BLOCK_COLORS["A2"]),
    ]
    positions = [1, 2, 3, 5, 6, 7, 8]
    values = [latency_values(block) for _, block, _ in groups]
    colors = [color for _, _, color in groups]
    rng = np.random.default_rng(seed)

    fig, ax = plt.subplots(figsize=(11.5, 5.2))
    boxes = ax.boxplot(
        values,
        positions=positions,
        widths=0.62,
        patch_artist=True,
        showfliers=False,
        medianprops={"color": "#102A2E", "linewidth": 1.5},
    )
    for patch, color in zip(boxes["boxes"], colors):
        patch.set_facecolor(color)
        patch.set_alpha(0.3)
        patch.set_edgecolor(color)
    for position, group, color in zip(positions, values, colors):
        jitter = rng.normal(0, 0.07, len(group))
        ax.scatter(
            np.full(len(group), position) + jitter,
            group,
            s=22,
            alpha=0.65,
            color=color,
            edgecolors="white",
            linewidths=0.3,
        )
        ax.text(
            position,
            max(group) + 55,
            f"{statistics.median(group):.0f}",
            ha="center",
            color=color,
            fontweight="bold",
        )
    ax.axvline(4, color="#B6B0A5", linewidth=1)
    ax.set_xticks(positions, [label for label, _, _ in groups])
    ax.set_ylabel("INVITE to provisional response (ms)")
    ax.set_title("Probe latency across days and network blocks")
    ax.text(2, 1.02, "Day 1", transform=ax.get_xaxis_transform(), ha="center")
    ax.text(6.5, 1.02, "Day 2", transform=ax.get_xaxis_transform(), ha="center")
    save_figure(fig, output_dir, "cross_day_latency")


def run(config_path: Path, captures_root: Path, output_dir: Path) -> None:
    config = load_json(config_path)
    days = [load_day(item, captures_root) for item in config["days"]]
    if [day.label for day in days] != ["Day 1", "Day 2"]:
        raise ValueError("Cross-day comparison expects Day 1 followed by Day 2")
    day1, day2 = days
    control_spec = config["controls"][0]
    if control_spec["day"] != "Day 2":
        raise ValueError("The unchanged-network control must belong to Day 2")
    control = load_control(control_spec, captures_root)
    output_dir.mkdir(parents=True, exist_ok=True)

    latency_rows = [
        summarize_block(day.label, day.blocks[label])
        for day in days
        for label in ("A1", "B", "A2")
    ]
    latency_rows.insert(4, summarize_block("Day 2", control))
    write_csv(output_dir / "cross_day_latency_summary.csv", latency_rows)

    rng = np.random.default_rng(int(config["randomSeed"]))
    iterations = int(config["bootstrapIterations"])
    permutation_iterations = int(config["permutationIterations"])
    day1_b = latency_values(day1.blocks["B"])
    day1_a = [latency_values(day1.blocks[label]) for label in ("A1", "A2")]
    day2_b = latency_values(day2.blocks["B"])
    day2_standard_a = [latency_values(day2.blocks[label]) for label in ("A1", "A2")]
    day2_controlled_a = [
        latency_values(day2.blocks["A1"]),
        latency_values(control),
        latency_values(day2.blocks["A2"]),
    ]
    effects = []
    effect_samples: dict[str, np.ndarray] = {}
    for label, b_values, a_groups in (
        ("Day 1", day1_b, day1_a),
        ("Day 2 standard A-B-A", day2_b, day2_standard_a),
        ("Day 2 with unchanged-A control", day2_b, day2_controlled_a),
    ):
        effect, low, high, samples = bootstrap_network_effect(
            b_values, a_groups, rng, iterations
        )
        pooled_a = [value for group in a_groups for value in group]
        effects.append(
            {
                "comparison": label,
                "b_minus_pooled_a_median_ms": effect,
                "ci_low_ms": low,
                "ci_high_ms": high,
                "permutation_p": permutation_median_p_value(
                    b_values, pooled_a, rng, permutation_iterations
                ),
                "cliffs_delta": cliffs_delta(b_values, pooled_a),
            }
        )
        effect_samples[label] = samples
    effect_difference = effect_samples["Day 1"] - effect_samples[
        "Day 2 with unchanged-A control"
    ]
    effects.append(
        {
            "comparison": "Day 1 effect minus Day 2 controlled effect",
            "b_minus_pooled_a_median_ms": effects[0]["b_minus_pooled_a_median_ms"]
            - effects[2]["b_minus_pooled_a_median_ms"],
            "ci_low_ms": float(np.quantile(effect_difference, 0.025)),
            "ci_high_ms": float(np.quantile(effect_difference, 0.975)),
            "permutation_p": "",
            "cliffs_delta": "",
        }
    )
    write_csv(output_dir / "network_effects.csv", effects)

    control_features = {
        name: values[control.spec.label]
        for name, values in candidate_feature_sets([control]).items()
    }
    candidate_rows = []
    for feature in day1.features:
        first = day1.features[feature]
        second = day2.features[feature]
        day1_a = first["A1"] | first["A2"]
        day2_a = second["A1"] | control_features[feature] | second["A2"]
        day1_aba = aba_return(first)
        day2_aba = aba_return(second)
        unchanged_control_stable = (
            second["A1"] == control_features[feature] == second["A2"]
        )
        candidate_rows.append(
            {
                "feature": feature,
                "day1_aba_return": day1_aba,
                "day2_aba_return": day2_aba,
                "day2_a1_equals_unchanged_a": second["A1"]
                == control_features[feature],
                "day2_unchanged_a_equals_a2": control_features[feature]
                == second["A2"],
                "day2_all_a_stable": unchanged_control_stable,
                "cross_day_a_jaccard": jaccard(day1_a, day2_a),
                "cross_day_b_jaccard": jaccard(first["B"], second["B"]),
                "replicated_network_candidate": day1_aba
                and day2_aba
                and unchanged_control_stable,
            }
        )
    write_csv(output_dir / "candidate_replication.csv", candidate_rows)

    configure_plot_style()
    plot_latency(day1, day2, control, output_dir, int(config["randomSeed"]))

    summary = {(row["day"], row["block"]): row for row in latency_rows}
    b_vs_control_difference = summary[("Day 2", "B")]["median_ms"] - summary[
        ("Day 2", "A-extra")
    ]["median_ms"]
    b_vs_control_p = permutation_median_p_value(
        day2_b, latency_values(control), rng, permutation_iterations
    )
    replicated_count = sum(row["replicated_network_candidate"] for row in candidate_rows)
    candidate_table = markdown_table(
        ["Feature", "Day 1 A-B-A", "Day 2 A-B-A", "All Day 2 A stable", "Replicated"],
        [
            [
                row["feature"],
                "yes" if row["day1_aba_return"] else "no",
                "yes" if row["day2_aba_return"] else "no",
                "yes" if row["day2_all_a_stable"] else "no",
                "yes" if row["replicated_network_candidate"] else "no",
            ]
            for row in candidate_rows
        ],
    )
    latency_table = markdown_table(
        ["Day", "Block", "Network", "n", "Median", "IQR", "SD"],
        [
            [
                row["day"],
                row["block"],
                row["network"],
                row["n"],
                f"{row['median_ms']:.1f} ms",
                f"{row['q1_ms']:.1f}-{row['q3_ms']:.1f} ms",
                f"{row['sd_ms']:.1f} ms",
            ]
            for row in latency_rows
        ],
    )
    report = f"""# Cross-Day SIP Observability Assessment

## Latency

{latency_table}

- Day 1 hotspot minus pooled university-Wi-Fi median effect:
  **{effects[0]['b_minus_pooled_a_median_ms']:.1f} ms**
  (95% bootstrap CI {effects[0]['ci_low_ms']:.1f} to {effects[0]['ci_high_ms']:.1f}).
- Day 2 hotspot minus pooled A1/A2 effect:
  **{effects[1]['b_minus_pooled_a_median_ms']:.1f} ms**
  (95% CI {effects[1]['ci_low_ms']:.1f} to {effects[1]['ci_high_ms']:.1f}).
- Including the accidental unchanged-network A control, the Day 2 effect is
  **{effects[2]['b_minus_pooled_a_median_ms']:.1f} ms**
  (95% CI {effects[2]['ci_low_ms']:.1f} to {effects[2]['ci_high_ms']:.1f}).
- Corrected B was **{b_vs_control_difference:.1f} ms** relative to the immediately
  preceding unchanged-A control (permutation p {b_vs_control_p:.4f}).

The standard A-B-A direction repeated, but its magnitude fell substantially on
Day 2. More importantly, the unchanged-network control reached a median of
**{summary[('Day 2', 'A-extra')]['median_ms']:.1f} ms**, compared with
**{summary[('Day 2', 'B')]['median_ms']:.1f} ms** on corrected B. Therefore the
observed latency is not specific to the target using the hotspot.

## Caller-Visible Fields

{candidate_table}

Day 1's path-field patterns did not replicate on Day 2. The path fields changed
between Day 2 A1 and the unchanged-network A control, while that control and A2
matched. Their cross-day value overlap was also zero. This behavior is consistent
with dynamic IMS registration or intermediary resource assignment, not a stable
target-network identifier. `Server` remained stable but did not distinguish A
from B. `Cellular-Network-Info` remains useful only for Cell information when
present.

## Conclusion

**{replicated_count} caller-visible feature sets survived the complete replication
rule.** The two days show that network/path conditions can influence latency, but
neither latency nor the additional SIP fields reliably identify university Wi-Fi
versus hotspot under the current controls. This is a useful negative result: the
Day 1 candidates should not be used for target-network or physical-location
inference without a stronger, independently replicated signal.
"""
    (output_dir / "report.md").write_text(report, encoding="utf-8")
    print(f"Cross-day comparison complete: {output_dir}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--captures-root", type=Path, default=Path("captures"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    run(args.config, args.captures_root, args.output)


if __name__ == "__main__":
    main()
