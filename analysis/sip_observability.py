#!/usr/bin/env python3
"""Aggregate caller-side SIP observability analysis without exporting identifiers."""

from __future__ import annotations

import argparse
import base64
import bisect
import collections
import csv
import hashlib
import ipaddress
import json
import math
import re
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


BLOCK_COLORS = {
    "A1": "#246A73",
    "B": "#D46A3A",
    "A2": "#4F8A5B",
}
MESSAGE_ORDER = {
    "100/INVITE": 100,
    "183/INVITE": 183,
    "200/CANCEL": 200,
    "487/INVITE": 487,
}
STAGE_LABELS = {
    "invite_to_100_ms": "INVITE to 100",
    "hundred_to_provisional_ms": "100 to provisional",
    "invite_to_provisional_ms": "INVITE to provisional",
    "capture_to_native_pr_ms": "Capture to native PR",
    "pr_to_cancel_ok_ms": "PR to CANCEL 200",
    "pr_to_invite_487_ms": "PR to INVITE 487",
}


@dataclass(frozen=True)
class BlockSpec:
    label: str
    condition_label: str
    ground_truth_condition_label: str
    caller_session_id: str
    ground_truth_file: str
    network_name: str
    primary_wifi_frequency_mhz: int


@dataclass
class BlockData:
    spec: BlockSpec
    caller: dict[str, Any]
    ground_truth: dict[str, Any]
    metadata: dict[str, Any]
    manifest: dict[str, Any]
    native_rows: list[dict[str, Any]]
    primary: list[dict[str, Any]]
    sensitivity: list[dict[str, Any]]
    validation: dict[str, Any]


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def read_jsonl_files(paths: Iterable[Path]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for path in paths:
        with path.open(encoding="utf-8", errors="replace") as handle:
            for line_number, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError as error:
                    raise ValueError(f"Malformed JSONL at {path}:{line_number}: {error}") from error
    return sorted(
        rows,
        key=lambda row: (
            row.get("unix_ms", 0),
            row.get("elapsed_ns", 0),
            row.get("process_id", 0),
            row.get("sequence", 0),
        ),
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_manifest(research_dir: Path, manifest: dict[str, Any]) -> dict[str, Any]:
    invalid: list[str] = []
    for item in manifest.get("files", []):
        path = research_dir / item["path"]
        valid = (
            path.exists()
            and path.stat().st_size == item["bytes"]
            and sha256_file(path) == item["sha256"]
        )
        if not valid:
            invalid.append(item["path"])
    return {
        "manifest_file_count": len(manifest.get("files", [])),
        "invalid_manifest_files": invalid,
        "native_copy_errors": manifest.get("nativeCaptureCopyErrors", 0),
        "context_write_failures": manifest.get("callerContextWriteFailures", 0),
    }


def nearest_sample(
    samples: Sequence[dict[str, Any]], timestamps: Sequence[int], timestamp_ms: int
) -> tuple[dict[str, Any], int]:
    index = bisect.bisect_left(timestamps, timestamp_ms)
    candidates = [candidate for candidate in (index - 1, index) if 0 <= candidate < len(samples)]
    if not candidates:
        raise ValueError("Ground-truth session contains no samples")
    best = min(candidates, key=lambda candidate: abs(timestamps[candidate] - timestamp_ms))
    return samples[best], abs(timestamps[best] - timestamp_ms)


def registered_signal_dbm(sample: dict[str, Any]) -> int | None:
    cells = (sample.get("radio") or {}).get("cells", [])
    values = [
        cell.get("signalDbm")
        for cell in cells
        if cell.get("registered") and cell.get("signalDbm") is not None
    ]
    return values[0] if values else None


def build_observation(
    block: BlockSpec,
    attempt: dict[str, Any],
    ground_truth: dict[str, Any],
    gap_ms: int,
) -> dict[str, Any]:
    device = ground_truth.get("deviceContext") or {}
    return {
        "block": block.label,
        "network": block.network_name,
        "attempt": attempt,
        "ground_truth": ground_truth,
        "ground_truth_gap_ms": gap_ms,
        "target_transport": device.get("activeTransport"),
        "target_wifi_frequency_mhz": device.get("wifiFrequencyMhz"),
        "target_wifi_rssi_dbm": device.get("wifiRssiDbm"),
        "target_wifi_link_mbps": device.get("wifiLinkSpeedMbps"),
        "target_cell_signal_dbm": registered_signal_dbm(ground_truth),
        "target_cell_key": ground_truth.get("servingCellStableKey"),
    }


def load_block(
    spec: BlockSpec,
    captures_root: Path,
    campaign_id: str,
    primary_count: int,
    max_ground_truth_gap_ms: int,
) -> BlockData:
    root = captures_root / spec.caller_session_id
    sanitized = root / "sanitized"
    raw = root / "raw" / spec.caller_session_id
    research = sanitized / f"{spec.caller_session_id}_research"
    caller = load_json(sanitized / f"{spec.caller_session_id}.json")
    metadata = load_json(raw / "session_metadata.json")
    manifest = load_json(research / "manifest.json")
    ground_truth = load_json(root / "ground_truth" / spec.ground_truth_file)
    native_paths = sorted((raw / "native").glob("sip_capture.jsonl*"))
    native_rows = read_jsonl_files(native_paths)

    checks = validate_manifest(research, manifest)
    runtime = [row for row in native_rows if row.get("record_type") == "runtime_event"]
    summaries = [row for row in runtime if row.get("event") == "capture_summary"]
    checks.update(
        {
            "native_starts": sum(row.get("event") == "process_started" for row in runtime),
            "native_finishes": sum(row.get("event") == "process_finished" for row in runtime),
            "capture_summaries": len(summaries),
            "dropped_events": sum(row.get("dropped_event_count", 0) for row in summaries),
        }
    )

    if metadata.get("campaignId") != campaign_id or ground_truth.get("campaignId") != campaign_id:
        raise ValueError(f"Campaign mismatch in {spec.label}")
    if metadata.get("conditionLabel") != spec.condition_label:
        raise ValueError(f"Caller condition mismatch in {spec.label}")
    if ground_truth.get("conditionLabel") != spec.ground_truth_condition_label:
        raise ValueError(f"Target condition mismatch in {spec.label}")
    if checks["invalid_manifest_files"]:
        raise ValueError(f"Manifest hash failure in {spec.label}: {checks['invalid_manifest_files']}")
    if checks["native_copy_errors"] or checks["context_write_failures"] or checks["dropped_events"]:
        raise ValueError(f"Capture integrity failure in {spec.label}: {checks}")

    target_samples = ground_truth.get("samples", [])
    target_timestamps = [sample["recordedAtMillis"] for sample in target_samples]
    observations: list[dict[str, Any]] = []
    for attempt in sorted(
        caller.get("samples", []), key=lambda row: row.get("responseReceivedAtMillis") or row["recordedAtMillis"]
    ):
        if attempt.get("deltaMs") is None or not attempt.get("probeId"):
            continue
        event_time = attempt.get("responseReceivedAtMillis") or attempt["recordedAtMillis"]
        target, gap_ms = nearest_sample(target_samples, target_timestamps, event_time)
        observations.append(build_observation(spec, attempt, target, gap_ms))

    eligible = [
        observation
        for observation in observations
        if observation["ground_truth_gap_ms"] <= max_ground_truth_gap_ms
        and observation["target_transport"] == "wifi"
        and observation["target_wifi_frequency_mhz"] == spec.primary_wifi_frequency_mhz
    ]
    if len(eligible) < primary_count:
        raise ValueError(
            f"{spec.label} has {len(eligible)} eligible attempts; {primary_count} required"
        )
    checks["completed_attempts"] = len(observations)
    checks["eligible_attempts"] = len(eligible)
    checks["primary_attempts"] = primary_count
    return BlockData(
        spec=spec,
        caller=caller,
        ground_truth=ground_truth,
        metadata=metadata,
        manifest=manifest,
        native_rows=native_rows,
        primary=eligible[:primary_count],
        sensitivity=observations,
        validation=checks,
    )


def numeric(values: Iterable[Any]) -> list[float]:
    return [float(value) for value in values if value is not None and math.isfinite(float(value))]


def percentile(values: Sequence[float], quantile: float) -> float:
    return float(np.quantile(np.asarray(values, dtype=float), quantile))


def bootstrap_median_ci(
    values: Sequence[float], rng: np.random.Generator, iterations: int
) -> tuple[float, float]:
    array = np.asarray(values, dtype=float)
    samples = rng.choice(array, size=(iterations, len(array)), replace=True)
    medians = np.median(samples, axis=1)
    low, high = np.quantile(medians, [0.025, 0.975])
    return float(low), float(high)


def bootstrap_median_difference_ci(
    first: Sequence[float],
    second: Sequence[float],
    rng: np.random.Generator,
    iterations: int,
) -> tuple[float, float]:
    left = np.asarray(first, dtype=float)
    right = np.asarray(second, dtype=float)
    left_samples = rng.choice(left, size=(iterations, len(left)), replace=True)
    right_samples = rng.choice(right, size=(iterations, len(right)), replace=True)
    differences = np.median(left_samples, axis=1) - np.median(right_samples, axis=1)
    low, high = np.quantile(differences, [0.025, 0.975])
    return float(low), float(high)


def permutation_median_p_value(
    first: Sequence[float],
    second: Sequence[float],
    rng: np.random.Generator,
    iterations: int,
) -> float:
    left = np.asarray(first, dtype=float)
    right = np.asarray(second, dtype=float)
    observed = abs(float(np.median(left) - np.median(right)))
    combined = np.concatenate((left, right))
    exceedances = 0
    for _ in range(iterations):
        shuffled = rng.permutation(combined)
        difference = abs(float(np.median(shuffled[: len(left)]) - np.median(shuffled[len(left) :])))
        exceedances += difference >= observed - 1e-12
    return (exceedances + 1) / (iterations + 1)


def cliffs_delta(first: Sequence[float], second: Sequence[float]) -> float:
    left = np.asarray(first, dtype=float)
    right = np.asarray(second, dtype=float)
    return float(np.sign(left[:, None] - right[None, :]).sum() / (len(left) * len(right)))


def holm_adjust(p_values: Sequence[float]) -> list[float]:
    order = sorted(range(len(p_values)), key=lambda index: p_values[index])
    adjusted = [0.0] * len(p_values)
    running = 0.0
    count = len(p_values)
    for rank, index in enumerate(order):
        running = max(running, min(1.0, (count - rank) * p_values[index]))
        adjusted[index] = running
    return adjusted


def wilson_interval(successes: int, total: int, z: float = 1.96) -> tuple[float, float]:
    if total == 0:
        return 0.0, 0.0
    proportion = successes / total
    denominator = 1 + z * z / total
    center = (proportion + z * z / (2 * total)) / denominator
    margin = z * math.sqrt(proportion * (1 - proportion) / total + z * z / (4 * total * total)) / denominator
    return center - margin, center + margin


def summarize_numeric(
    block: str,
    dataset: str,
    values: Sequence[float],
    rng: np.random.Generator,
    bootstrap_iterations: int,
) -> dict[str, Any]:
    ci_low, ci_high = bootstrap_median_ci(values, rng, bootstrap_iterations)
    return {
        "dataset": dataset,
        "block": block,
        "n": len(values),
        "mean": statistics.fmean(values),
        "sd": statistics.stdev(values) if len(values) > 1 else 0.0,
        "median": statistics.median(values),
        "median_ci_low": ci_low,
        "median_ci_high": ci_high,
        "q1": percentile(values, 0.25),
        "q3": percentile(values, 0.75),
        "p90": percentile(values, 0.90),
        "min": min(values),
        "max": max(values),
    }


def native_by_call(block: BlockData) -> dict[str, list[dict[str, Any]]]:
    result: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    for row in block.native_rows:
        if row.get("record_type") != "transport_payload" or not row.get("call_id"):
            continue
        result[row["call_id"]].append(row)
    for rows in result.values():
        rows.sort(key=lambda row: row.get("elapsed_ns", 0))
    return result


def first_response(
    rows: Sequence[dict[str, Any]], status: int, cseq_method: str
) -> dict[str, Any] | None:
    return next(
        (
            row
            for row in rows
            if row.get("direction") == "incoming"
            and row.get("status") == status
            and row.get("cseq_method") == cseq_method
        ),
        None,
    )


def duration_from_elapsed(
    start_ms: float | None, response: dict[str, Any] | None
) -> float | None:
    if start_ms is None or response is None:
        return None
    duration = response["elapsed_ns"] / 1_000_000 - start_ms
    return duration if duration >= 0 else None


def enrich_transactions(blocks: Sequence[BlockData]) -> None:
    for block in blocks:
        calls = native_by_call(block)
        for dataset in (block.primary, block.sensitivity):
            for observation in dataset:
                attempt = observation["attempt"]
                rows = calls.get(attempt["probeId"], [])
                trying = first_response(rows, 100, "INVITE")
                provisional = first_response(rows, 183, "INVITE")
                cancel_ok = first_response(rows, 200, "CANCEL")
                invite_terminated = first_response(rows, 487, "INVITE")
                trying_ms = trying["elapsed_ns"] / 1_000_000 if trying else None
                provisional_ms = provisional["elapsed_ns"] / 1_000_000 if provisional else None
                pr_ms = attempt.get("prMs")
                observation["native_rows"] = rows
                observation["invite_to_100_ms"] = (
                    trying_ms - attempt["inviteMs"] if trying_ms is not None and attempt.get("inviteMs") is not None else None
                )
                observation["hundred_to_provisional_ms"] = (
                    pr_ms - trying_ms if pr_ms is not None and trying_ms is not None else None
                )
                observation["invite_to_provisional_ms"] = attempt.get("deltaMs")
                observation["capture_to_native_pr_ms"] = (
                    pr_ms - provisional_ms if pr_ms is not None and provisional_ms is not None else None
                )
                observation["pr_to_cancel_ok_ms"] = duration_from_elapsed(pr_ms, cancel_ok)
                observation["pr_to_invite_487_ms"] = duration_from_elapsed(pr_ms, invite_terminated)
                observation["trying_copies"] = sum(
                    row.get("direction") == "incoming" and row.get("status") == 100 for row in rows
                )
                observation["provisional_copies"] = sum(
                    row.get("direction") == "incoming" and row.get("status") == 183 for row in rows
                )
                observation["response_sequence"] = response_sequence(rows)


def response_sequence(rows: Sequence[dict[str, Any]]) -> str:
    sequence: list[str] = []
    for row in rows:
        if row.get("direction") != "incoming" or not row.get("status"):
            continue
        token = f"{row['status']}/{row.get('cseq_method') or 'UNKNOWN'}"
        if not sequence or sequence[-1] != token:
            sequence.append(token)
    return " -> ".join(sequence)


def header_values(row: dict[str, Any] | None, header_name: str) -> list[str]:
    if row is None:
        return []
    return [
        str(header.get("value", ""))
        for header in row.get("headers", [])
        if str(header.get("name", "")).lower() == header_name.lower()
    ]


def combined_header_value(row: dict[str, Any] | None, header_name: str) -> str | None:
    values = header_values(row, header_name)
    return "\n".join(values) if values else None


def value_fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="replace")).hexdigest()


def first_messages(observation: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for row in observation.get("native_rows", []):
        if row.get("direction") != "incoming" or not row.get("status"):
            continue
        key = f"{row['status']}/{row.get('cseq_method') or 'UNKNOWN'}"
        result.setdefault(key, row)
    return result


def header_analysis(blocks: Sequence[BlockData]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    message_types = sorted(
        {
            message_type
            for block in blocks
            for observation in block.primary
            for message_type in first_messages(observation)
        },
        key=lambda item: MESSAGE_ORDER.get(item, 999),
    )
    presence_rows: list[dict[str, Any]] = []
    behavior_rows: list[dict[str, Any]] = []
    for message_type in message_types:
        header_names = sorted(
            {
                str(header.get("name", "")).lower()
                for block in blocks
                for observation in block.primary
                for header in first_messages(observation).get(message_type, {}).get("headers", [])
            }
        )
        for header_name in header_names:
            block_stats: dict[str, dict[str, Any]] = {}
            for block in blocks:
                values: list[str] = []
                messages = 0
                for observation in block.primary:
                    message = first_messages(observation).get(message_type)
                    if message is None:
                        continue
                    messages += 1
                    value = combined_header_value(message, header_name)
                    if value is not None:
                        values.append(value)
                fingerprints = collections.Counter(value_fingerprint(value) for value in values)
                dominant_fingerprint, dominant_count = fingerprints.most_common(1)[0] if fingerprints else ("", 0)
                ci_low, ci_high = wilson_interval(len(values), messages)
                block_stats[block.spec.label] = {
                    "messages": messages,
                    "present": len(values),
                    "presence_rate": len(values) / messages if messages else 0.0,
                    "presence_ci_low": ci_low,
                    "presence_ci_high": ci_high,
                    "unique_values": len(fingerprints),
                    "dominant_count": dominant_count,
                    "dominant_fraction": dominant_count / len(values) if values else 0.0,
                    "dominant_fingerprint": dominant_fingerprint,
                }
                presence_rows.append(
                    {
                        "message_type": message_type,
                        "header_name": header_name,
                        "block": block.spec.label,
                        **{key: value for key, value in block_stats[block.spec.label].items() if key != "dominant_fingerprint"},
                    }
                )
            a1, middle, a2 = (block_stats[label] for label in ("A1", "B", "A2"))
            stable = all(stat["dominant_fraction"] >= 0.8 for stat in (a1, middle, a2))
            aba_return = bool(
                stable
                and a1["dominant_fingerprint"]
                and a1["dominant_fingerprint"] == a2["dominant_fingerprint"]
                and a1["dominant_fingerprint"] != middle["dominant_fingerprint"]
            )
            all_same = bool(
                stable
                and a1["dominant_fingerprint"]
                and a1["dominant_fingerprint"] == middle["dominant_fingerprint"] == a2["dominant_fingerprint"]
            )
            transaction_specific = all(
                stat["present"] > 0 and stat["unique_values"] / stat["present"] >= 0.8
                for stat in (a1, middle, a2)
            )
            behavior_rows.append(
                {
                    "message_type": message_type,
                    "header_name": header_name,
                    "a1_present": a1["present"],
                    "b_present": middle["present"],
                    "a2_present": a2["present"],
                    "a1_unique_values": a1["unique_values"],
                    "b_unique_values": middle["unique_values"],
                    "a2_unique_values": a2["unique_values"],
                    "a1_dominant_fraction": a1["dominant_fraction"],
                    "b_dominant_fraction": middle["dominant_fraction"],
                    "a2_dominant_fraction": a2["dominant_fraction"],
                    "aba_return": aba_return,
                    "constant_across_blocks": all_same,
                    "transaction_specific": transaction_specific,
                }
            )
    return presence_rows, behavior_rows


def parse_sdp(row: dict[str, Any] | None) -> list[str]:
    if row is None or not row.get("body_base64"):
        return []
    try:
        body = base64.b64decode(row["body_base64"]).decode("latin1")
    except (ValueError, UnicodeDecodeError):
        return []
    return [line.strip() for line in body.replace("\r", "").split("\n") if line.strip()]


def extract_ip_token(value: str) -> str | None:
    for token in re.findall(r"(?:\d{1,3}\.){3}\d{1,3}|(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}", value):
        try:
            return str(ipaddress.ip_address(token))
        except ValueError:
            continue
    return None


def contact_host(row: dict[str, Any] | None) -> str | None:
    values = header_values(row, "contact")
    if not values:
        return None
    match = re.search(r"@(?:\[([^]]+)\]|([^;>:]+))(?::\d+)?", values[0])
    return (match.group(1) or match.group(2)) if match else None


def record_route_value(row: dict[str, Any] | None) -> str | None:
    return combined_header_value(row, "record-route")


def sdp_origin_address(row: dict[str, Any] | None) -> str | None:
    line = next((line for line in parse_sdp(row) if line.startswith("o=")), "")
    return extract_ip_token(line)


def sdp_connection_addresses(row: dict[str, Any] | None) -> set[str]:
    return {
        address
        for line in parse_sdp(row)
        if line.startswith("c=")
        for address in [extract_ip_token(line)]
        if address
    }


def first_183(observation: dict[str, Any]) -> dict[str, Any] | None:
    return first_response(observation.get("native_rows", []), 183, "INVITE")


def feature_sets(
    blocks: Sequence[BlockData], extractor: Callable[[dict[str, Any]], Iterable[str]]
) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for block in blocks:
        values: set[str] = set()
        for observation in block.primary:
            values.update(value for value in extractor(observation) if value)
        result[block.spec.label] = values
    return result


def singleton_feature(
    observation: dict[str, Any], extractor: Callable[[dict[str, Any] | None], str | None]
) -> Iterable[str]:
    value = extractor(first_183(observation))
    return [value] if value else []


def aba_return(sets: dict[str, set[str]]) -> bool:
    return bool(sets["A1"] and sets["A1"] == sets["A2"] and sets["A1"] != sets["B"])


def candidate_feature_sets(
    blocks: Sequence[BlockData],
) -> dict[str, dict[str, set[str]]]:
    return {
        "Contact host": feature_sets(blocks, lambda obs: singleton_feature(obs, contact_host)),
        "SDP origin address": feature_sets(blocks, lambda obs: singleton_feature(obs, sdp_origin_address)),
        "Record-Route": feature_sets(blocks, lambda obs: singleton_feature(obs, record_route_value)),
        "SDP connection pool": feature_sets(
            blocks, lambda obs: sdp_connection_addresses(first_183(obs))
        ),
        "Response source address": feature_sets(
            blocks,
            lambda obs: [first_183(obs).get("source_address")]
            if first_183(obs) and first_183(obs).get("source_address")
            else [],
        ),
        "Server": feature_sets(
            blocks,
            lambda obs: header_values(first_183(obs), "server"),
        ),
        "Cellular-Network-Info": feature_sets(
            blocks,
            lambda obs: header_values(first_183(obs), "cellular-network-info"),
        ),
    }


def candidate_analysis(blocks: Sequence[BlockData]) -> tuple[list[dict[str, Any]], dict[str, dict[str, set[str]]]]:
    features = candidate_feature_sets(blocks)
    contact_matches_origin = all(
        contact_host(first_183(observation)) == sdp_origin_address(first_183(observation))
        for block in blocks
        for observation in block.primary
    )
    contact_aba = aba_return(features["Contact host"]) and aba_return(
        features["SDP origin address"]
    )
    route_aba = aba_return(features["Record-Route"])
    media_pool_aba = aba_return(features["SDP connection pool"])
    response_source_aba = aba_return(features["Response source address"])
    rows = [
        {
            "feature": "Contact host / SDP origin address",
            "a1_values": len(features["Contact host"]["A1"]),
            "b_values": len(features["Contact host"]["B"]),
            "a2_values": len(features["Contact host"]["A2"]),
            "aba_return": contact_aba,
            "observation": (
                "Contact and SDP origin agree; the value changed on B and returned on A2"
                if contact_matches_origin and contact_aba
                else "Contact and SDP origin agree, but the value did not meet the A-B-A return rule"
                if contact_matches_origin
                else "Contact and SDP origin do not consistently agree"
            ),
            "likely_origin": "Target-side IMS contact or topology-hidden address",
            "can_imply": "A repeatable target registration/network-path state",
            "cannot_imply": "A physical location or specific AP without attribution controls",
            "status": "network-path candidate; requires cross-day replication",
        },
        {
            "feature": "Record-Route",
            "a1_values": len(features["Record-Route"]["A1"]),
            "b_values": len(features["Record-Route"]["B"]),
            "a2_values": len(features["Record-Route"]["A2"]),
            "aba_return": route_aba,
            "observation": (
                "Stable per block; changed on B and returned on A2"
                if route_aba
                else "Stable per block but did not meet the A-B-A return rule"
            ),
            "likely_origin": "Carrier proxy/SBC routing",
            "can_imply": "A changed IMS signaling path",
            "cannot_imply": "Direct target identity, AP, or physical location",
            "status": "intermediary-path candidate; requires cross-day replication",
        },
        {
            "feature": "SDP connection-address pool",
            "a1_values": len(features["SDP connection pool"]["A1"]),
            "b_values": len(features["SDP connection pool"]["B"]),
            "a2_values": len(features["SDP connection pool"]["A2"]),
            "aba_return": media_pool_aba,
            "observation": (
                "Carrier media-address pool changed on B and returned on A2"
                if media_pool_aba
                else "Carrier media-address pool did not meet the A-B-A return rule"
            ),
            "likely_origin": "Carrier media gateway/resource pool",
            "can_imply": "A changed carrier routing/resource context",
            "cannot_imply": "The target's public address or physical location",
            "status": "intermediary-path candidate; requires cross-day replication",
        },
        {
            "feature": "Incoming response source address",
            "a1_values": len(features["Response source address"]["A1"]),
            "b_values": len(features["Response source address"]["B"]),
            "a2_values": len(features["Response source address"]["A2"]),
            "aba_return": response_source_aba,
            "observation": (
                "Private carrier endpoint changed on B and returned on A2"
                if response_source_aba
                else "Private carrier endpoint did not meet the A-B-A return rule"
            ),
            "likely_origin": "Caller-side IMS/ePDG/SBC endpoint",
            "can_imply": "The signaling path or caller tunnel changed",
            "cannot_imply": "A target-originated value without further controls",
            "status": "confounded intermediary signal",
        },
        {
            "feature": "Cellular-Network-Info",
            "a1_values": len(features["Cellular-Network-Info"]["A1"]),
            "b_values": len(features["Cellular-Network-Info"]["B"]),
            "a2_values": len(features["Cellular-Network-Info"]["A2"]),
            "aba_return": False,
            "observation": "Presence and value follow Cell availability rather than A-B-A",
            "likely_origin": "Target cellular state",
            "can_imply": "Cell identity when the header is present",
            "cannot_imply": "Target Wi-Fi network; the header is frequently absent",
            "status": "cell-related control",
        },
        {
            "feature": "Server",
            "a1_values": len(features["Server"]["A1"]),
            "b_values": len(features["Server"]["B"]),
            "a2_values": len(features["Server"]["A2"]),
            "aba_return": False,
            "observation": "Constant across all three blocks",
            "likely_origin": "Carrier application server",
            "can_imply": "The serving IMS platform family",
            "cannot_imply": "Network A versus B",
            "status": "not discriminative",
        },
    ]
    return rows, features


def write_csv(path: Path, rows: Sequence[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fieldnames: list[str] = []
    for row in rows:
        for key in row:
            if key not in fieldnames:
                fieldnames.append(key)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def anonymized_cell_aliases(blocks: Sequence[BlockData]) -> dict[str, str]:
    values = sorted(
        {
            observation["target_cell_key"]
            for block in blocks
            for observation in block.primary
            if observation.get("target_cell_key")
        },
        key=lambda value: hashlib.sha256(value.encode()).hexdigest(),
    )
    return {value: f"cell_{index + 1}" for index, value in enumerate(values)}


def primary_rows(blocks: Sequence[BlockData]) -> list[dict[str, Any]]:
    aliases = anonymized_cell_aliases(blocks)
    rows: list[dict[str, Any]] = []
    for block in blocks:
        start = block.caller["startedAtMillis"]
        for index, observation in enumerate(block.primary, 1):
            attempt = observation["attempt"]
            event_time = attempt.get("responseReceivedAtMillis") or attempt["recordedAtMillis"]
            rows.append(
                {
                    "block": block.spec.label,
                    "network": block.spec.network_name,
                    "attempt_index": index,
                    "seconds_from_block_start": (event_time - start) / 1000,
                    "delta_ms": attempt["deltaMs"],
                    "cell_header_present": attempt.get("cid", -1) >= 0,
                    "target_transport": observation["target_transport"],
                    "target_wifi_frequency_mhz": observation["target_wifi_frequency_mhz"],
                    "target_wifi_rssi_dbm": observation["target_wifi_rssi_dbm"],
                    "target_wifi_link_mbps": observation["target_wifi_link_mbps"],
                    "target_cell_signal_dbm": observation["target_cell_signal_dbm"],
                    "target_cell_alias": aliases.get(observation.get("target_cell_key"), "unknown"),
                    "ground_truth_gap_ms": observation["ground_truth_gap_ms"],
                    "caller_wifi_rssi_dbm": attempt.get("wifiRssiDbm"),
                    "caller_wifi_link_mbps": attempt.get("wifiLinkSpeedMbps"),
                    "interval_since_previous_probe_ms": attempt.get("intervalSincePreviousProbeMs"),
                    "invite_to_100_ms": observation.get("invite_to_100_ms"),
                    "hundred_to_provisional_ms": observation.get("hundred_to_provisional_ms"),
                    "provisional_copies": observation.get("provisional_copies"),
                    "response_sequence": observation.get("response_sequence"),
                }
            )
    return rows


def latency_analysis(
    blocks: Sequence[BlockData],
    rng: np.random.Generator,
    bootstrap_iterations: int,
    permutation_iterations: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    summaries: list[dict[str, Any]] = []
    primary_values: dict[str, list[float]] = {}
    for dataset_name, selector in (
        ("primary_balanced", lambda block: block.primary),
        ("sensitivity_all_completed", lambda block: block.sensitivity),
    ):
        for block in blocks:
            values = numeric(obs["attempt"].get("deltaMs") for obs in selector(block))
            summaries.append(
                summarize_numeric(
                    block.spec.label, dataset_name, values, rng, bootstrap_iterations
                )
            )
            if dataset_name == "primary_balanced":
                primary_values[block.spec.label] = values

    pair_rows: list[dict[str, Any]] = []
    for first_label, second_label in (("A1", "B"), ("B", "A2"), ("A1", "A2")):
        first = primary_values[first_label]
        second = primary_values[second_label]
        difference = statistics.median(first) - statistics.median(second)
        ci_low, ci_high = bootstrap_median_difference_ci(
            first, second, rng, bootstrap_iterations
        )
        pair_rows.append(
            {
                "first": first_label,
                "second": second_label,
                "median_difference_ms": difference,
                "difference_ci_low_ms": ci_low,
                "difference_ci_high_ms": ci_high,
                "cliffs_delta": cliffs_delta(first, second),
                "permutation_p": permutation_median_p_value(
                    first, second, rng, permutation_iterations
                ),
            }
        )
    adjusted = holm_adjust([row["permutation_p"] for row in pair_rows])
    for row, value in zip(pair_rows, adjusted):
        row["holm_adjusted_p"] = value
    return summaries, pair_rows


def stage_analysis(
    blocks: Sequence[BlockData],
    rng: np.random.Generator,
    bootstrap_iterations: int,
    permutation_iterations: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    metrics = list(STAGE_LABELS)
    summaries: list[dict[str, Any]] = []
    values_by_metric: dict[str, dict[str, list[float]]] = collections.defaultdict(dict)
    for metric in metrics:
        for block in blocks:
            values = numeric(observation.get(metric) for observation in block.primary)
            if not values:
                continue
            summary = summarize_numeric(
                block.spec.label, "primary_balanced", values, rng, bootstrap_iterations
            )
            summary["metric"] = metric
            summary["metric_label"] = STAGE_LABELS[metric]
            summaries.append(summary)
            values_by_metric[metric][block.spec.label] = values

    pair_rows: list[dict[str, Any]] = []
    for metric in ("invite_to_100_ms", "hundred_to_provisional_ms", "invite_to_provisional_ms"):
        for first_label, second_label in (("A1", "B"), ("B", "A2"), ("A1", "A2")):
            first = values_by_metric[metric][first_label]
            second = values_by_metric[metric][second_label]
            ci_low, ci_high = bootstrap_median_difference_ci(
                first, second, rng, bootstrap_iterations
            )
            pair_rows.append(
                {
                    "metric": metric,
                    "first": first_label,
                    "second": second_label,
                    "median_difference_ms": statistics.median(first) - statistics.median(second),
                    "difference_ci_low_ms": ci_low,
                    "difference_ci_high_ms": ci_high,
                    "cliffs_delta": cliffs_delta(first, second),
                    "permutation_p": permutation_median_p_value(
                        first, second, rng, permutation_iterations
                    ),
                }
            )
    for metric in ("invite_to_100_ms", "hundred_to_provisional_ms", "invite_to_provisional_ms"):
        indices = [index for index, row in enumerate(pair_rows) if row["metric"] == metric]
        adjusted = holm_adjust([pair_rows[index]["permutation_p"] for index in indices])
        for index, value in zip(indices, adjusted):
            pair_rows[index]["holm_adjusted_p"] = value
    return summaries, pair_rows


def response_analysis(blocks: Sequence[BlockData]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    sequence_rows: list[dict[str, Any]] = []
    retransmission_rows: list[dict[str, Any]] = []
    for block in blocks:
        sequences = collections.Counter(
            observation.get("response_sequence", "") for observation in block.primary
        )
        for sequence, count in sequences.most_common():
            sequence_rows.append(
                {
                    "block": block.spec.label,
                    "response_sequence": sequence,
                    "count": count,
                    "fraction": count / len(block.primary),
                }
            )
        copies = [int(observation.get("provisional_copies", 0)) for observation in block.primary]
        duplicate_count = sum(value > 1 for value in copies)
        ci_low, ci_high = wilson_interval(duplicate_count, len(copies))
        retransmission_rows.append(
            {
                "block": block.spec.label,
                "n": len(copies),
                "mean_183_copies": statistics.fmean(copies),
                "median_183_copies": statistics.median(copies),
                "transactions_with_duplicate_183": duplicate_count,
                "duplicate_rate": duplicate_count / len(copies),
                "duplicate_rate_ci_low": ci_low,
                "duplicate_rate_ci_high": ci_high,
                "max_183_copies": max(copies),
            }
        )
    return sequence_rows, retransmission_rows


def context_summary(blocks: Sequence[BlockData]) -> list[dict[str, Any]]:
    fields = {
        "target_wifi_rssi_dbm": lambda obs: obs.get("target_wifi_rssi_dbm"),
        "target_wifi_link_mbps": lambda obs: obs.get("target_wifi_link_mbps"),
        "target_cell_signal_dbm": lambda obs: obs.get("target_cell_signal_dbm"),
        "caller_wifi_rssi_dbm": lambda obs: obs["attempt"].get("wifiRssiDbm"),
        "caller_wifi_link_mbps": lambda obs: obs["attempt"].get("wifiLinkSpeedMbps"),
        "ground_truth_gap_ms": lambda obs: obs.get("ground_truth_gap_ms"),
    }
    rows: list[dict[str, Any]] = []
    for block in blocks:
        row: dict[str, Any] = {
            "block": block.spec.label,
            "network": block.spec.network_name,
            "n": len(block.primary),
            "target_wifi_frequency_mhz": block.spec.primary_wifi_frequency_mhz,
            "target_cell_count": len(
                {obs.get("target_cell_key") for obs in block.primary if obs.get("target_cell_key")}
            ),
        }
        for name, getter in fields.items():
            values = numeric(getter(observation) for observation in block.primary)
            row[f"{name}_median"] = statistics.median(values) if values else None
            row[f"{name}_q1"] = percentile(values, 0.25) if values else None
            row[f"{name}_q3"] = percentile(values, 0.75) if values else None
        rows.append(row)
    return rows


def cell_header_summary(blocks: Sequence[BlockData]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for block in blocks:
        present = sum(obs["attempt"].get("cid", -1) >= 0 for obs in block.primary)
        ci_low, ci_high = wilson_interval(present, len(block.primary))
        rows.append(
            {
                "block": block.spec.label,
                "present": present,
                "total": len(block.primary),
                "presence_rate": present / len(block.primary),
                "presence_ci_low": ci_low,
                "presence_ci_high": ci_high,
            }
        )
    return rows


def cell_stratified_analysis(
    blocks: Sequence[BlockData],
    rng: np.random.Generator,
    bootstrap_iterations: int,
    permutation_iterations: int,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    aliases = anonymized_cell_aliases(blocks)
    grouped: dict[tuple[str, str], list[float]] = collections.defaultdict(list)
    for block in blocks:
        for observation in block.primary:
            key = observation.get("target_cell_key")
            if key:
                grouped[(key, block.spec.label)].append(float(observation["attempt"]["deltaMs"]))
    candidate_keys = {key for key, _ in list(grouped)}
    shared = sorted(
        {
            key
            for key in candidate_keys
            if all(grouped[(key, label)] for label in ("A1", "B", "A2"))
        },
        key=lambda key: aliases[key],
    )
    if not shared:
        return [], {
            "shared_cell_count": 0,
            "equal_cell_adjusted_difference_ms": None,
            "difference_ci_low_ms": None,
            "difference_ci_high_ms": None,
            "stratified_permutation_p": None,
        }

    def equal_cell_statistic(values: dict[tuple[str, str], Sequence[float]]) -> float:
        differences = []
        for key in shared:
            pooled_a = list(values[(key, "A1")]) + list(values[(key, "A2")])
            differences.append(
                statistics.median(values[(key, "B")]) - statistics.median(pooled_a)
            )
        return statistics.fmean(differences)

    rows: list[dict[str, Any]] = []
    for key in shared:
        a1 = grouped[(key, "A1")]
        middle = grouped[(key, "B")]
        a2 = grouped[(key, "A2")]
        pooled_a = a1 + a2
        rows.append(
            {
                "cell_alias": aliases[key],
                "a1_n": len(a1),
                "a1_median_ms": statistics.median(a1),
                "b_n": len(middle),
                "b_median_ms": statistics.median(middle),
                "a2_n": len(a2),
                "a2_median_ms": statistics.median(a2),
                "b_minus_pooled_a_median_ms": statistics.median(middle)
                - statistics.median(pooled_a),
            }
        )

    observed = equal_cell_statistic(grouped)
    bootstrap_values: list[float] = []
    for _ in range(bootstrap_iterations):
        sampled: dict[tuple[str, str], Sequence[float]] = {}
        for key in shared:
            for label in ("A1", "B", "A2"):
                values = np.asarray(grouped[(key, label)], dtype=float)
                sampled[(key, label)] = rng.choice(values, len(values), replace=True)
        bootstrap_values.append(equal_cell_statistic(sampled))
    ci_low, ci_high = np.quantile(bootstrap_values, [0.025, 0.975])

    exceedances = 0
    for _ in range(permutation_iterations):
        shuffled: dict[tuple[str, str], Sequence[float]] = {}
        for key in shared:
            counts = {label: len(grouped[(key, label)]) for label in ("A1", "B", "A2")}
            combined = np.asarray(
                grouped[(key, "A1")] + grouped[(key, "B")] + grouped[(key, "A2")],
                dtype=float,
            )
            permuted = rng.permutation(combined)
            a1_end = counts["A1"]
            b_end = a1_end + counts["B"]
            shuffled[(key, "A1")] = permuted[:a1_end]
            shuffled[(key, "B")] = permuted[a1_end:b_end]
            shuffled[(key, "A2")] = permuted[b_end:]
        exceedances += abs(equal_cell_statistic(shuffled)) >= abs(observed) - 1e-12
    summary = {
        "shared_cell_count": len(shared),
        "equal_cell_adjusted_difference_ms": observed,
        "difference_ci_low_ms": float(ci_low),
        "difference_ci_high_ms": float(ci_high),
        "stratified_permutation_p": (exceedances + 1) / (permutation_iterations + 1),
    }
    return rows, summary


def save_figure(fig: plt.Figure, output_dir: Path, name: str) -> None:
    fig.savefig(output_dir / f"{name}.png", dpi=180, bbox_inches="tight", facecolor="white")
    fig.savefig(output_dir / f"{name}.svg", bbox_inches="tight", facecolor="white")
    plt.close(fig)


def configure_plot_style() -> None:
    plt.rcParams.update(
        {
            "font.family": "DejaVu Sans",
            "font.size": 10,
            "axes.titlesize": 13,
            "axes.labelsize": 10,
            "axes.spines.top": False,
            "axes.spines.right": False,
            "axes.grid": True,
            "grid.alpha": 0.2,
            "figure.facecolor": "white",
        }
    )


def plot_latency(blocks: Sequence[BlockData], output_dir: Path, seed: int) -> None:
    labels = [block.spec.label for block in blocks]
    values = [numeric(obs["attempt"]["deltaMs"] for obs in block.primary) for block in blocks]
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.4), gridspec_kw={"width_ratios": [1, 1.2]})
    box = axes[0].boxplot(
        values,
        tick_labels=labels,
        patch_artist=True,
        showfliers=False,
        widths=0.55,
    )
    for patch, label in zip(box["boxes"], labels):
        patch.set_facecolor(BLOCK_COLORS[label])
        patch.set_alpha(0.28)
        patch.set_edgecolor(BLOCK_COLORS[label])
    rng = np.random.default_rng(seed)
    for index, (label, group) in enumerate(zip(labels, values), 1):
        jitter = rng.uniform(-0.11, 0.11, len(group))
        axes[0].scatter(
            np.full(len(group), index) + jitter,
            group,
            s=24,
            color=BLOCK_COLORS[label],
            alpha=0.72,
            edgecolors="white",
            linewidths=0.35,
        )
        median = statistics.median(group)
        axes[0].text(index, max(group) * 1.03, f"median {median:.0f}", ha="center", color=BLOCK_COLORS[label])
    axes[0].set_title("Probe latency by network block")
    axes[0].set_ylabel("INVITE to provisional response (ms)")
    axes[0].set_xlabel(
        f"A1/A2: {blocks[0].spec.network_name}; B: {blocks[1].spec.network_name}"
    )

    for label, group in zip(labels, values):
        ordered = np.sort(np.asarray(group))
        ecdf = np.arange(1, len(ordered) + 1) / len(ordered)
        axes[1].step(ordered, ecdf, where="post", label=label, color=BLOCK_COLORS[label], linewidth=2.2)
    axes[1].set_title("Latency cumulative distribution")
    axes[1].set_xlabel("Latency (ms)")
    axes[1].set_ylabel("Fraction of attempts")
    axes[1].set_ylim(0, 1.02)
    axes[1].legend(frameon=False)
    fig.suptitle("Day 1 A-B-A latency", fontsize=15, fontweight="bold")
    fig.tight_layout()
    save_figure(fig, output_dir, "latency_by_network")


def plot_stage_latency(stage_rows: Sequence[dict[str, Any]], output_dir: Path) -> None:
    metrics = ["invite_to_100_ms", "hundred_to_provisional_ms"]
    labels = ["A1", "B", "A2"]
    lookup = {(row["metric"], row["block"]): row for row in stage_rows}
    x = np.arange(len(metrics))
    width = 0.23
    fig, ax = plt.subplots(figsize=(8.4, 4.7))
    for offset_index, label in enumerate(labels):
        rows = [lookup[(metric, label)] for metric in metrics]
        medians = [row["median"] for row in rows]
        errors = [
            [row["median"] - row["median_ci_low"] for row in rows],
            [row["median_ci_high"] - row["median"] for row in rows],
        ]
        positions = x + (offset_index - 1) * width
        ax.bar(
            positions,
            medians,
            width,
            yerr=errors,
            capsize=4,
            color=BLOCK_COLORS[label],
            alpha=0.86,
            label=label,
        )
        for position, median in zip(positions, medians):
            ax.text(position, median + 25, f"{median:.0f}", ha="center", fontsize=9)
    ax.set_xticks(x, ["INVITE to 100 Trying", "100 Trying to provisional"])
    ax.set_ylabel("Median duration (ms), bootstrap 95% CI")
    ax.set_title("Where the hotspot latency increase occurs", fontweight="bold")
    ax.legend(frameon=False, ncol=3)
    ax.set_ylim(bottom=0)
    fig.tight_layout()
    save_figure(fig, output_dir, "stage_latency_by_network")


def plot_observability(
    features: dict[str, dict[str, set[str]]],
    cell_rows: Sequence[dict[str, Any]],
    output_dir: Path,
) -> None:
    names = ["Contact host", "SDP origin address", "Record-Route", "SDP connection pool", "Response source address", "Server"]
    labels = ["A1", "B", "A2"]
    matrix = np.zeros((len(names), len(labels)))
    for row_index, name in enumerate(names):
        baseline = features[name]["A1"]
        for column_index, label in enumerate(labels):
            matrix[row_index, column_index] = 0 if features[name][label] == baseline else 1

    fig, axes = plt.subplots(1, 2, figsize=(11.5, 5), gridspec_kw={"width_ratios": [1.55, 1]})
    axes[0].imshow(matrix, cmap=matplotlib.colors.ListedColormap(["#DDEBE7", "#F3C7AC"]), vmin=0, vmax=1, aspect="auto")
    axes[0].set_xticks(range(3), labels)
    axes[0].set_yticks(range(len(names)), names)
    axes[0].set_title("Opaque caller-visible value equality")
    for row_index in range(len(names)):
        for column_index in range(3):
            text = "same as A1" if matrix[row_index, column_index] == 0 else "different"
            axes[0].text(column_index, row_index, text, ha="center", va="center", fontsize=8.5)
    axes[0].grid(False)

    lookup = {row["block"]: row for row in cell_rows}
    rates = [lookup[label]["presence_rate"] * 100 for label in labels]
    low = [(lookup[label]["presence_rate"] - lookup[label]["presence_ci_low"]) * 100 for label in labels]
    high = [(lookup[label]["presence_ci_high"] - lookup[label]["presence_rate"]) * 100 for label in labels]
    axes[1].bar(labels, rates, color=[BLOCK_COLORS[label] for label in labels], alpha=0.86, yerr=[low, high], capsize=4)
    for index, rate in enumerate(rates):
        axes[1].text(index, rate + 3, f"{rate:.0f}%", ha="center")
    axes[1].set_ylim(0, 110)
    axes[1].set_ylabel("Transactions containing header (%)")
    axes[1].set_title("Cellular-Network-Info presence")
    fig.suptitle("Day 1 caller-visible SIP observations", fontsize=15, fontweight="bold")
    fig.tight_layout()
    save_figure(fig, output_dir, "sip_observability_aba")


def plot_retransmissions(rows: Sequence[dict[str, Any]], output_dir: Path) -> None:
    labels = [row["block"] for row in rows]
    rates = [row["duplicate_rate"] * 100 for row in rows]
    low = [(row["duplicate_rate"] - row["duplicate_rate_ci_low"]) * 100 for row in rows]
    high = [(row["duplicate_rate_ci_high"] - row["duplicate_rate"]) * 100 for row in rows]
    fig, ax = plt.subplots(figsize=(7, 4.5))
    ax.bar(labels, rates, color=[BLOCK_COLORS[label] for label in labels], alpha=0.86, yerr=[low, high], capsize=5)
    for index, row in enumerate(rows):
        ax.text(index, rates[index] + 3, f"{rates[index]:.0f}%\nmean copies {row['mean_183_copies']:.2f}", ha="center", fontsize=9)
    ax.set_ylim(0, 110)
    ax.set_ylabel("Transactions with repeated 183 (%)")
    ax.set_title("183 retransmission behavior", fontweight="bold")
    fig.tight_layout()
    save_figure(fig, output_dir, "retransmissions_by_network")


def format_p(value: float) -> str:
    return "<0.0001" if value < 0.0001 else f"{value:.4f}"


def markdown_table(headers: Sequence[str], rows: Sequence[Sequence[Any]]) -> str:
    output = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    output.extend("| " + " | ".join(str(value) for value in row) + " |" for row in rows)
    return "\n".join(output)


def write_report(
    output_dir: Path,
    config: dict[str, Any],
    blocks: Sequence[BlockData],
    latency_rows: Sequence[dict[str, Any]],
    latency_tests: Sequence[dict[str, Any]],
    stage_rows: Sequence[dict[str, Any]],
    stage_tests: Sequence[dict[str, Any]],
    retransmission_rows: Sequence[dict[str, Any]],
    cell_rows: Sequence[dict[str, Any]],
    candidate_rows: Sequence[dict[str, Any]],
    context_rows: Sequence[dict[str, Any]],
    cell_stratified_rows: Sequence[dict[str, Any]],
    cell_stratified_summary: dict[str, Any],
) -> None:
    primary_latency = {row["block"]: row for row in latency_rows if row["dataset"] == "primary_balanced"}
    sensitivity_latency = {
        row["block"]: row
        for row in latency_rows
        if row["dataset"] == "sensitivity_all_completed"
    }
    stages = {(row["metric"], row["block"]): row for row in stage_rows}
    stage_test_lookup = {
        (row["metric"], row["first"], row["second"]): row for row in stage_tests
    }
    tests = {(row["first"], row["second"]): row for row in latency_tests}
    retransmissions = {row["block"]: row for row in retransmission_rows}
    cell = {row["block"]: row for row in cell_rows}
    context = {row["block"]: row for row in context_rows}
    labels = ["A1", "B", "A2"]
    stable_candidate_count = sum(bool(row["aba_return"]) for row in candidate_rows)

    latency_table = markdown_table(
        ["Block", "Network", "n", "Median ms", "95% CI", "IQR ms", "Mean ms", "SD ms"],
        [
            [
                label,
                next(block.spec.network_name for block in blocks if block.spec.label == label),
                primary_latency[label]["n"],
                f"{primary_latency[label]['median']:.1f}",
                f"{primary_latency[label]['median_ci_low']:.1f}-{primary_latency[label]['median_ci_high']:.1f}",
                f"{primary_latency[label]['q1']:.1f}-{primary_latency[label]['q3']:.1f}",
                f"{primary_latency[label]['mean']:.1f}",
                f"{primary_latency[label]['sd']:.1f}",
            ]
            for label in labels
        ],
    )
    stage_table = markdown_table(
        ["Block", "INVITE to 100 median", "100 to provisional median", "Total median"],
        [
            [
                label,
                f"{stages[('invite_to_100_ms', label)]['median']:.1f} ms",
                f"{stages[('hundred_to_provisional_ms', label)]['median']:.1f} ms",
                f"{stages[('invite_to_provisional_ms', label)]['median']:.1f} ms",
            ]
            for label in labels
        ],
    )
    context_table = markdown_table(
        [
            "Block",
            "Target Wi-Fi RSSI",
            "Target link",
            "Target Cell signal",
            "Caller Wi-Fi RSSI",
            "Caller link",
            "GT gap",
        ],
        [
            [
                label,
                f"{context[label]['target_wifi_rssi_dbm_median']:.1f} dBm",
                f"{context[label]['target_wifi_link_mbps_median']:.1f} Mbps",
                f"{context[label]['target_cell_signal_dbm_median']:.1f} dBm",
                f"{context[label]['caller_wifi_rssi_dbm_median']:.1f} dBm",
                f"{context[label]['caller_wifi_link_mbps_median']:.1f} Mbps",
                f"{context[label]['ground_truth_gap_ms_median']:.0f} ms",
            ]
            for label in labels
        ],
    )
    candidate_table = markdown_table(
        ["Feature", "A-B-A return", "Interpretation", "Status"],
        [
            [row["feature"], "yes" if row["aba_return"] else "no", row["can_imply"], row["status"]]
            for row in candidate_rows
        ],
    )
    validation_table = markdown_table(
        ["Block", "Completed", "Eligible", "Primary", "Dropped", "Manifest"],
        [
            [
                block.spec.label,
                block.validation["completed_attempts"],
                block.validation["eligible_attempts"],
                block.validation["primary_attempts"],
                block.validation["dropped_events"],
                "valid" if not block.validation["invalid_manifest_files"] else "invalid",
            ]
            for block in blocks
        ],
    )
    cell_stratified_table = markdown_table(
        ["Cell", "A1 n/median", "B n/median", "A2 n/median", "B minus pooled A"],
        [
            [
                row["cell_alias"],
                f"{row['a1_n']} / {row['a1_median_ms']:.1f} ms",
                f"{row['b_n']} / {row['b_median_ms']:.1f} ms",
                f"{row['a2_n']} / {row['a2_median_ms']:.1f} ms",
                f"{row['b_minus_pooled_a_median_ms']:.1f} ms",
            ]
            for row in cell_stratified_rows
        ],
    )

    a1_b = tests[("A1", "B")]
    b_a2 = tests[("B", "A2")]
    a1_a2 = tests[("A1", "A2")]
    report_title = str(config.get("reportTitle", "Caller-Side SIP Observability Analysis"))
    primary_count = int(primary_latency["A1"]["n"])
    total_primary = sum(int(primary_latency[label]["n"]) for label in labels)
    a_network = next(block.spec.network_name for block in blocks if block.spec.label == "A1")
    b_network = next(block.spec.network_name for block in blocks if block.spec.label == "B")
    stable_candidates = [row["feature"] for row in candidate_rows if row["aba_return"]]
    stable_candidate_text = (
        ", ".join(stable_candidates)
        if stable_candidates
        else "No recorded candidate"
    )
    if cell_stratified_rows:
        cell_control_text = f"""The following ground-truth serving Cells appeared in all three primary blocks:

{cell_stratified_table}

Giving each shared Cell equal weight, B minus pooled A1/A2 was
**{cell_stratified_summary['equal_cell_adjusted_difference_ms']:.1f} ms**
(95% stratified bootstrap CI {cell_stratified_summary['difference_ci_low_ms']:.1f}
to {cell_stratified_summary['difference_ci_high_ms']:.1f}; stratified permutation
p {format_p(cell_stratified_summary['stratified_permutation_p'])})."""
    else:
        cell_control_text = (
            "No serving Cell had primary observations in all three blocks, so a "
            "within-Cell latency comparison was not possible."
        )

    report = f"""# {report_title}

## Scope

This is an exploratory A-B-A analysis. Network A is **{a_network}** and Network B
is **{b_network}**. The primary dataset contains {primary_count} eligible attempts
per block ({total_primary} total). The sensitivity dataset contains all
{sum(len(block.sensitivity) for block in blocks)} completed attempts. Each SIP
Call-ID contributes one transaction; repeated packets are analyzed separately.

{validation_table}

## Latency

Observed probe latency by block:

{latency_table}

Using every completed attempt instead of the balanced subset produced the same
ordering: A1 **{sensitivity_latency['A1']['median']:.1f} ms**, B
**{sensitivity_latency['B']['median']:.1f} ms**, and A2
**{sensitivity_latency['A2']['median']:.1f} ms**.

- A1 minus B median difference: **{a1_b['median_difference_ms']:.1f} ms**
  (95% bootstrap CI {a1_b['difference_ci_low_ms']:.1f} to {a1_b['difference_ci_high_ms']:.1f};
  Holm-adjusted permutation p {format_p(a1_b['holm_adjusted_p'])}; Cliff's delta {a1_b['cliffs_delta']:.3f}).
- B minus A2 median difference: **{b_a2['median_difference_ms']:.1f} ms**
  (95% bootstrap CI {b_a2['difference_ci_low_ms']:.1f} to {b_a2['difference_ci_high_ms']:.1f};
  Holm-adjusted p {format_p(b_a2['holm_adjusted_p'])}).
- A1 minus A2 median difference: **{a1_a2['median_difference_ms']:.1f} ms**
  (Holm-adjusted p {format_p(a1_a2['holm_adjusted_p'])}).

Timing decomposition:

{stage_table}

Relative to A1, B minus A1 was
**{-stage_test_lookup[('invite_to_100_ms', 'A1', 'B')]['median_difference_ms']:.1f} ms**
before `100 Trying` (Holm-adjusted p
{format_p(stage_test_lookup[('invite_to_100_ms', 'A1', 'B')]['holm_adjusted_p'])})
and **{-stage_test_lookup[('hundred_to_provisional_ms', 'A1', 'B')]['median_difference_ms']:.1f} ms**
between `100 Trying` and the provisional response (adjusted p
{format_p(stage_test_lookup[('hundred_to_provisional_ms', 'A1', 'B')]['holm_adjusted_p'])}).

### Serving-Cell Control

{cell_control_text}

## Caller-Visible Information Beyond Cell ID

{candidate_table}

{stable_candidate_count} recorded features showed an A-B-A return pattern.
{stable_candidate_text} met the within-sequence return rule. These values still
require cross-day comparison and provenance controls before attribution to the
target rather than an IMS intermediary.

`Cellular-Network-Info` appeared in {cell['A1']['present']}/{cell['A1']['total']} A1 transactions,
{cell['B']['present']}/{cell['B']['total']} B transactions, and
{cell['A2']['present']}/{cell['A2']['total']} A2
transactions. Missingness is not interpreted as a network-A/network-B classifier
without separate replication. All observed `cell-info-age` values were zero.

## Retransmissions

- A1: {retransmissions['A1']['transactions_with_duplicate_183']}/{retransmissions['A1']['n']} transactions
  had repeated 183 responses (mean {retransmissions['A1']['mean_183_copies']:.2f} copies).
- B: {retransmissions['B']['transactions_with_duplicate_183']}/{retransmissions['B']['n']}
  (mean {retransmissions['B']['mean_183_copies']:.2f}).
- A2: {retransmissions['A2']['transactions_with_duplicate_183']}/{retransmissions['A2']['n']}
  (mean {retransmissions['A2']['mean_183_copies']:.2f}).

The app persisted one attempt per Call-ID, so repeated provisional responses are
packet-level repeats rather than duplicate history records.

## Recorded Context

{context_table}

Target Wi-Fi RSSI medians were {context['A1']['target_wifi_rssi_dbm_median']:.1f},
{context['B']['target_wifi_rssi_dbm_median']:.1f}, and
{context['A2']['target_wifi_rssi_dbm_median']:.1f} dBm for A1, B, and A2. Caller
link-speed medians were {context['A1']['caller_wifi_link_mbps_median']:.1f},
{context['B']['caller_wifi_link_mbps_median']:.1f}, and
{context['A2']['caller_wifi_link_mbps_median']:.1f} Mbps. These measurements are
controls, not proof of causality. A and B differ in AP, NAT, internet exit,
backhaul, congestion, and potentially ePDG/IMS routing, so the experiment cannot
isolate those components.

## Limitations

- This report analyzes one A-B-A sequence. Cross-day replication must be assessed
  separately rather than inferred from this report.
- The phone-hotspot and university-Wi-Fi conditions change several mechanisms at
  once. The result supports network-path distinguishability, not AP-specific or
  physical-location attribution.
- Primary attempts are within {int(config['maxGroundTruthGapMs'])} ms of a target
  snapshot, and the exact alignment gap is retained in the aggregate dataset.
- The primary comparison includes only the configured Wi-Fi frequency for each
  block. Other completed attempts remain in the sensitivity dataset.
- LocationData BSSID hashes are session-salted, so cross-session BSSID equality
  cannot be verified from exports. Condition labels and frequency/transport state
  define the primary comparison.
- The target was manually known to be stationary. GPS-derived movement labels are
  not used because location jitter falsely classified movement.

## Conclusion

In this sequence, B minus A1 median latency was
**{-a1_b['median_difference_ms']:.1f} ms**, while A2 minus A1 was
**{-a1_a2['median_difference_ms']:.1f} ms**. The sequence tests whether the two
network conditions are caller-distinguishable; it does **not** show that latency
or SIP fields identify a physical location. Cross-day repeatability and controls
remain necessary for any stronger claim.
"""
    (output_dir / "report.md").write_text(report, encoding="utf-8")


def parse_config(path: Path) -> tuple[dict[str, Any], list[BlockSpec]]:
    config = load_json(path)
    blocks = [
        BlockSpec(
            label=item["label"],
            condition_label=item["conditionLabel"],
            ground_truth_condition_label=item.get(
                "groundTruthConditionLabel", item["conditionLabel"]
            ),
            caller_session_id=item["callerSessionId"],
            ground_truth_file=item["groundTruthFile"],
            network_name=item["networkName"],
            primary_wifi_frequency_mhz=int(item["primaryWifiFrequencyMhz"]),
        )
        for item in config["blocks"]
    ]
    if [block.label for block in blocks] != ["A1", "B", "A2"]:
        raise ValueError("Config blocks must be ordered A1, B, A2")
    return config, blocks


def run_analysis(config_path: Path, captures_root: Path, output_dir: Path) -> None:
    config, specs = parse_config(config_path)
    output_dir.mkdir(parents=True, exist_ok=True)
    blocks = [
        load_block(
            spec,
            captures_root,
            config["campaignId"],
            int(config["primaryAttemptsPerBlock"]),
            int(config["maxGroundTruthGapMs"]),
        )
        for spec in specs
    ]
    enrich_transactions(blocks)
    rng = np.random.default_rng(int(config["randomSeed"]))
    bootstrap_iterations = int(config["bootstrapIterations"])
    permutation_iterations = int(config["permutationIterations"])

    latency_rows, latency_tests = latency_analysis(
        blocks, rng, bootstrap_iterations, permutation_iterations
    )
    stage_rows, stage_tests = stage_analysis(
        blocks, rng, bootstrap_iterations, permutation_iterations
    )
    header_presence, header_behavior = header_analysis(blocks)
    candidates, features = candidate_analysis(blocks)
    sequences, retransmissions = response_analysis(blocks)
    contexts = context_summary(blocks)
    cell_headers = cell_header_summary(blocks)
    cell_stratified_rows, cell_stratified_summary = cell_stratified_analysis(
        blocks, rng, bootstrap_iterations, permutation_iterations
    )

    write_csv(output_dir / "primary_attempts.csv", primary_rows(blocks))
    write_csv(output_dir / "latency_summary.csv", latency_rows)
    write_csv(output_dir / "latency_pairwise_tests.csv", latency_tests)
    write_csv(output_dir / "stage_timing_summary.csv", stage_rows)
    write_csv(output_dir / "stage_pairwise_tests.csv", stage_tests)
    write_csv(output_dir / "header_presence.csv", header_presence)
    write_csv(output_dir / "header_value_behavior.csv", header_behavior)
    write_csv(output_dir / "candidate_provenance.csv", candidates)
    write_csv(output_dir / "response_sequences.csv", sequences)
    write_csv(output_dir / "retransmission_summary.csv", retransmissions)
    write_csv(output_dir / "context_summary.csv", contexts)
    write_csv(output_dir / "cell_header_summary.csv", cell_headers)
    write_csv(output_dir / "cell_stratified_latency.csv", cell_stratified_rows)
    write_csv(
        output_dir / "cell_stratified_summary.csv", [cell_stratified_summary]
    )
    write_csv(
        output_dir / "capture_validation.csv",
        [{"block": block.spec.label, **block.validation} for block in blocks],
    )

    configure_plot_style()
    plot_latency(blocks, output_dir, int(config["randomSeed"]))
    plot_stage_latency(stage_rows, output_dir)
    plot_observability(features, cell_headers, output_dir)
    plot_retransmissions(retransmissions, output_dir)
    write_report(
        output_dir,
        config,
        blocks,
        latency_rows,
        latency_tests,
        stage_rows,
        stage_tests,
        retransmissions,
        cell_headers,
        candidates,
        contexts,
        cell_stratified_rows,
        cell_stratified_summary,
    )
    print(f"Analysis complete: {output_dir}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--captures-root", type=Path, default=Path("captures"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    run_analysis(args.config, args.captures_root, args.output)


if __name__ == "__main__":
    main()
