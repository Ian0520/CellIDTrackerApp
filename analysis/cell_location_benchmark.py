#!/usr/bin/env python3
"""Score empirical one-cell areas against held-out target GPS without API calls.

Train and test sessions are explicit. Output is aggregate only; no cells,
coordinates, phone numbers, or per-attempt locations are written.
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Any

if __package__:
    from .cell_sequence import load_json, nearest_truth, percentile
else:
    from cell_sequence import load_json, nearest_truth, percentile


@dataclass(frozen=True)
class PairedPoint:
    cell: tuple[str, int, int, int, int]
    latitude: float
    longitude: float
    google_latitude: float | None
    google_longitude: float | None
    google_radius_m: float | None


@dataclass(frozen=True)
class AreaEstimate:
    latitude: float
    longitude: float
    radius_m: float


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6_371_000 * 2 * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1 - a)))


def tower_key(attempt: dict[str, Any]) -> tuple[str, int, int, int, int] | None:
    values = (attempt.get("mcc"), attempt.get("mnc"), attempt.get("lac"), attempt.get("cid"))
    if any(value is None or int(value) < 0 for value in values):
        return None
    towers = json.loads(attempt.get("towersJson") or "[]")
    if not towers:
        return None
    radio = str(towers[0].get("radio") or "").lower()
    if radio not in ("lte", "nr", "wcdma", "gsm"):
        return None
    if tuple(towers[0].get(field) for field in ("mcc", "mnc", "lac", "cid")) != values:
        return None
    return radio, *(int(value) for value in values)


def enodeb_key(cell: tuple[str, int, int, int, int]) -> tuple[str, int, int, int, int] | None:
    radio, mcc, mnc, area, identity = cell
    if radio != "lte":
        return None
    return "lte-enodeb", mcc, mnc, 0, identity >> 8


def load_points(spec: dict[str, Any], captures_root: Path, max_gap_ms: int,
                max_truth_accuracy_m: float, max_truth_age_ms: int) -> list[PairedPoint]:
    session_id = spec["callerSessionId"]
    root = captures_root / session_id
    caller = load_json(root / "sanitized" / f"{session_id}.json")
    metadata = load_json(root / "raw" / session_id / "session_metadata.json")
    truth = load_json(root / "ground_truth" / spec["groundTruthFile"])
    if metadata.get("campaignId") != spec["campaignId"] or caller.get("campaignId") not in (None, spec["campaignId"]):
        raise ValueError(f"Caller campaign mismatch in {spec['label']}")
    if metadata.get("conditionLabel") != spec["conditionLabel"]:
        raise ValueError(f"Caller condition mismatch in {spec['label']}")
    if truth.get("campaignId") != spec["campaignId"]:
        raise ValueError(f"Target campaign mismatch in {spec['label']}")
    if truth.get("conditionLabel") != spec.get("groundTruthConditionLabel", spec["conditionLabel"]):
        raise ValueError(f"Target condition mismatch in {spec['label']}")
    samples = sorted(truth.get("samples", []), key=lambda item: item["recordedAtMillis"])
    timestamps = [item["recordedAtMillis"] for item in samples]
    points: list[PairedPoint] = []
    for attempt in caller.get("samples", []):
        cell = tower_key(attempt)
        if cell is None or not attempt.get("probeId"):
            continue
        event_time = attempt.get("responseReceivedAtMillis") or attempt.get("recordedAtMillis")
        sample = nearest_truth(samples, timestamps, event_time, max_gap_ms)
        if sample is None or sample.get("isMockLocation"):
            continue
        if sample.get("accuracyM") is None or sample["accuracyM"] > max_truth_accuracy_m:
            continue
        if sample.get("locationAgeMillis") is None or sample["locationAgeMillis"] > max_truth_age_ms:
            continue
        google_ok = (
            attempt.get("geolocationStatus") == "success"
            and all(attempt.get(field) is not None for field in ("estimatedLat", "estimatedLon", "estimatedAccuracyM"))
        )
        points.append(PairedPoint(
            cell=cell,
            latitude=float(sample["lat"]),
            longitude=float(sample["lon"]),
            google_latitude=float(attempt["estimatedLat"]) if google_ok else None,
            google_longitude=float(attempt["estimatedLon"]) if google_ok else None,
            google_radius_m=float(attempt["estimatedAccuracyM"]) if google_ok else None,
        ))
    return points


def fit_areas(points: list[PairedPoint], key_kind: str, min_points: int,
              radius_quantile: float) -> dict[tuple[str, int, int, int, int], AreaEstimate]:
    groups: dict[tuple[str, int, int, int, int], list[PairedPoint]] = collections.defaultdict(list)
    for point in points:
        key = point.cell if key_kind == "cell" else enodeb_key(point.cell)
        if key is not None:
            groups[key].append(point)
    areas: dict[tuple[str, int, int, int, int], AreaEstimate] = {}
    for key, samples in groups.items():
        if len(samples) < min_points:
            continue
        latitude = statistics.fmean(point.latitude for point in samples)
        longitude = statistics.fmean(point.longitude for point in samples)
        distances = [distance_m(latitude, longitude, point.latitude, point.longitude) for point in samples]
        areas[key] = AreaEstimate(latitude, longitude, percentile(distances, radius_quantile))
    return areas


def summarize(errors: list[float], contained: int, total_points: int) -> dict[str, Any]:
    return {
        "eligible_test_points": total_points,
        "predicted_points": len(errors),
        "abstained_points": total_points - len(errors),
        "median_error_m": round(statistics.median(errors), 1) if errors else None,
        "p90_error_m": round(percentile(errors, 0.9), 1) if errors else None,
        "within_reported_or_calibrated_radius": contained,
    }


def score(points: list[PairedPoint], areas: dict[tuple[str, int, int, int, int], AreaEstimate],
          key_kind: str) -> tuple[dict[str, Any], dict[int, float]]:
    errors: list[float] = []
    by_index: dict[int, float] = {}
    contained = 0
    for index, point in enumerate(points):
        key = point.cell if key_kind == "cell" else enodeb_key(point.cell)
        area = areas.get(key) if key is not None else None
        if area is None:
            continue
        error = distance_m(area.latitude, area.longitude, point.latitude, point.longitude)
        errors.append(error)
        by_index[index] = error
        contained += error <= area.radius_m
    return summarize(errors, contained, len(points)), by_index


def score_google(points: list[PairedPoint]) -> tuple[dict[str, Any], dict[int, float]]:
    errors: list[float] = []
    by_index: dict[int, float] = {}
    contained = 0
    for index, point in enumerate(points):
        if point.google_latitude is None or point.google_longitude is None or point.google_radius_m is None:
            continue
        error = distance_m(point.google_latitude, point.google_longitude, point.latitude, point.longitude)
        errors.append(error)
        by_index[index] = error
        contained += error <= point.google_radius_m
    return summarize(errors, contained, len(points)), by_index


def run(config: dict[str, Any], captures_root: Path) -> dict[str, Any]:
    train_specs = config["trainSessions"]
    test_specs = config["testSessions"]
    train_ids = {spec["callerSessionId"] for spec in train_specs}
    test_ids = {spec["callerSessionId"] for spec in test_specs}
    if not train_specs or not test_specs or train_ids & test_ids:
        raise ValueError("Training and test sessions must be nonempty and disjoint")
    train_days = {spec["day"] for spec in train_specs}
    test_days = {spec["day"] for spec in test_specs}
    if train_days & test_days:
        raise ValueError("Training and test sessions must use different days")
    gap = int(config.get("maxGroundTruthGapMs", 5000))
    accuracy = float(config.get("maxGroundLocationAccuracyM", 30))
    age = int(config.get("maxGroundLocationAgeMs", 5000))
    training = [point for spec in train_specs for point in load_points(spec, captures_root, gap, accuracy, age)]
    testing = [point for spec in test_specs for point in load_points(spec, captures_root, gap, accuracy, age)]
    if not training or not testing:
        raise ValueError("No quality-filtered paired cell/GPS points in train or test sessions")
    minimum = int(config.get("minTrainingPointsPerCell", 5))
    quantile = float(config.get("radiusQuantile", 0.9))
    if minimum < 1 or not 0 < quantile < 1:
        raise ValueError("Invalid minimum training count or radius quantile")
    cell_areas = fit_areas(training, "cell", minimum, quantile)
    enodeb_areas = fit_areas(training, "enodeb", minimum, quantile)
    cell_score, cell_errors = score(testing, cell_areas, "cell")
    enodeb_score, _ = score(testing, enodeb_areas, "enodeb")
    google_score, google_errors = score_google(testing)
    paired = sorted(set(cell_errors) & set(google_errors))
    return {
        "train_sessions": len(train_specs), "test_sessions": len(test_specs),
        "training_points": len(training), "test_points": len(testing),
        "trained_cells": len(cell_areas), "trained_enodeb_groups": len(enodeb_areas),
        "empirical_cell": cell_score, "empirical_enodeb": enodeb_score,
        "google_single_cell": google_score,
        "paired_cell_google": {
            "n": len(paired),
            "empirical_median_error_m": round(statistics.median(cell_errors[i] for i in paired), 1) if paired else None,
            "google_median_error_m": round(statistics.median(google_errors[i] for i in paired), 1) if paired else None,
        },
        "limits": "Day-separated held-out result for these routes and conditions only; repeated probes within a site are correlated.",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--captures-root", type=Path, default=Path("captures"))
    parser.add_argument("--output", type=Path, default=Path("captures/cell_location_benchmark"))
    args = parser.parse_args()
    result = run(load_json(args.config), args.captures_root)
    args.output.mkdir(parents=True, exist_ok=True)
    path = args.output / "summary.json"
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"Scored {result['test_points']} held-out paired observations")
    print(f"Aggregate report: {path}")


if __name__ == "__main__":
    main()
