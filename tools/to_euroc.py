#!/usr/bin/env python3
"""Convert a Stereo Passthrough recording into the EuRoC MAV dataset layout.

    python3 tools/to_euroc.py RECORDING_DIR OUTPUT_DIR [--gray] [--mid-exposure] [--uncalibrated-imu]

Produces OUTPUT_DIR/mav0/{cam0,cam1}/data/<timestamp_ns>.png, cam*/data.csv, cam*/sensor.yaml,
imu0/data.csv and imu0/sensor.yaml, as read by ORB-SLAM3, OpenVINS, Basalt, VINS-Fusion and
Kalibr. Needs only Python 3 and ffmpeg (for video recordings).

Timestamps are put on the IMU clock (CLOCK_BOOTTIME). The IMU's body frame is the Android
sensor frame of the device; camera extrinsics come from the recording's metadata.json and are
a starting point for a proper camera-IMU calibration (e.g. Kalibr), not a replacement for it.
"""
import argparse
import bisect
import csv
import json
import shutil
import statistics
import subprocess
import sys
import tempfile
from pathlib import Path


def read_csv(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def camera_clock_offset(rec, meta):
    """Nanoseconds to add to camera timestamps to put them on CLOCK_BOOTTIME."""
    if meta["timestamps"].get("camera_timestamps_comparable_with_imu"):
        return 0, "camera clock is REALTIME (BOOTTIME)"
    rows = read_csv(rec / "cam0" / "capture_results.csv")
    latency = statistics.median(int(r["arrival_ns"]) - int(r["timestamp_ns"]) for r in rows if r["timestamp_ns"])
    if 0 <= latency < 1e9:
        return 0, f"camera clock source UNKNOWN, but results arrive {latency / 1e6:.1f} ms after capture on BOOTTIME: no offset"
    offset = meta["clocks_at_start"]["boottime_minus_monotonic_ns"]
    return offset, f"camera clock source UNKNOWN and not BOOTTIME (latency {latency / 1e9:.1f} s): adding {offset} ns (MONOTONIC to BOOTTIME)"


def extract_frames(cam_dir, out_dir, gray):
    """Writes every stored frame as out_dir/<index>.png (video) or returns the JPEG paths."""
    video = cam_dir / "video.mp4"
    if video.exists():
        cmd = ["ffmpeg", "-v", "error", "-i", str(video), "-vsync", "0"]
        if gray:
            cmd += ["-pix_fmt", "gray"]
        cmd.append(str(out_dir / "%08d.png"))
        subprocess.run(cmd, check=True)
        return sorted(out_dir.glob("*.png"))
    return None


def mat_to_list(m):
    return [v for row in m for v in row]


def transform(rotation, translation):
    """Row-major 4x4 from a 3x3 rotation and a translation."""
    t = translation or [0.0, 0.0, 0.0]
    return [*rotation[0], t[0], *rotation[1], t[1], *rotation[2], t[2], 0.0, 0.0, 0.0, 1.0]


def write_camera_yaml(path, cam, meta, rectified):
    size = cam["size"]
    if rectified:
        r = cam["rectified"]
        intrinsics = [r["fx"], r["fy"], r["cx"], r["cy"]]
        distortion = [0.0, 0.0, 0.0, 0.0]
        rotation = r["device_from_virtual_rotation"]
    else:
        r = cam["raw"]
        intrinsics = [r["fx"], r["fy"], r["cx"], r["cy"]]
        d = r.get("opencv_radtan_k1_k2_p1_p2_k3") or [0.0] * 5
        distortion = d[:4]  # EuRoC radtan has k1, k2, p1, p2
        if len(d) > 4 and abs(d[4]) > 1e-9:
            print(f"  note: {cam['name']} has k3 = {d[4]}, which EuRoC's radtan model drops", file=sys.stderr)
        rotation = cam["device_from_camera_rotation"]
    translation = cam.get("position_m") if cam.get("pose_reference") == "GYROSCOPE" else None
    fps = meta["recording"]["target_fps"]
    lines = [
        "%YAML:1.0",
        f"# {cam['name']} ({cam['eye']} eye, camera {cam['camera']}), {'rectified' if rectified else 'raw'}",
        "sensor_type: camera",
        f"comment: {cam['name']} {cam['camera']}",
        "# Body frame = IMU = Android device sensor frame."
        + ("" if translation else " Translation unknown (lens pose not relative to the gyroscope): set to zero."),
        "T_BS:",
        "  cols: 4",
        "  rows: 4",
        "  data: [" + ", ".join(f"{v:.9f}" for v in transform(rotation, translation)) + "]",
        f"rate_hz: {fps}",
        f"resolution: [{size[0]}, {size[1]}]",
        "camera_model: pinhole",
        "intrinsics: [" + ", ".join(f"{v:.6f}" for v in intrinsics) + "]",
        "distortion_model: radial-tangential",
        "distortion_coefficients: [" + ", ".join(f"{v:.9f}" for v in distortion) + "]",
    ]
    path.write_text("\n".join(lines) + "\n")


def interpolate(times, values, t):
    i = bisect.bisect_left(times, t)
    if i == 0 or i == len(times):
        return None
    t0, t1 = times[i - 1], times[i]
    a = (t - t0) / (t1 - t0) if t1 != t0 else 0.0
    return [v0 + a * (v1 - v0) for v0, v1 in zip(values[i - 1], values[i])]


def convert_imu(rec, out, uncalibrated):
    suffix = "_uncalibrated" if uncalibrated else ""
    gyro_path = rec / "imu" / f"gyroscope{suffix}.csv"
    accel_path = rec / "imu" / f"accelerometer{suffix}.csv"
    if not gyro_path.exists() or not accel_path.exists():
        sys.exit(f"Missing {gyro_path.name} or {accel_path.name}; record those IMU streams or toggle --uncalibrated-imu")
    gyro = read_csv(gyro_path)
    accel = read_csv(accel_path)
    a_t = [int(r["timestamp_ns"]) for r in accel]
    a_v = [[float(r["ax"]), float(r["ay"]), float(r["az"])] for r in accel]
    rows = 0
    with open(out / "data.csv", "w") as f:
        f.write("#timestamp [ns],w_RS_S_x [rad s^-1],w_RS_S_y [rad s^-1],w_RS_S_z [rad s^-1],"
                "a_RS_S_x [m s^-2],a_RS_S_y [m s^-2],a_RS_S_z [m s^-2]\n")
        for r in gyro:
            t = int(r["timestamp_ns"])
            a = interpolate(a_t, a_v, t)
            if a is None:
                continue
            f.write(f"{t},{r['wx']},{r['wy']},{r['wz']},{a[0]:.9g},{a[1]:.9g},{a[2]:.9g}\n")
            rows += 1
    rate = (len(gyro) - 1) * 1e9 / (int(gyro[-1]["timestamp_ns"]) - int(gyro[0]["timestamp_ns"]))
    (out / "sensor.yaml").write_text("\n".join([
        "%YAML:1.0",
        "# Android device sensor frame (x right, y up, z out of the screen). Noise values are",
        "# placeholders: measure them (e.g. Allan variance) for your device.",
        "sensor_type: imu",
        "comment: gyroscope timestamps, accelerometer linearly interpolated",
        "T_BS:",
        "  cols: 4",
        "  rows: 4",
        "  data: [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0]",
        f"rate_hz: {rate:.1f}",
        "gyroscope_noise_density: 1.0e-3",
        "gyroscope_random_walk: 1.0e-5",
        "accelerometer_noise_density: 1.0e-2",
        "accelerometer_random_walk: 1.0e-4",
    ]) + "\n")
    print(f"imu0: {rows} samples at {rate:.1f} Hz ({gyro_path.name} + {accel_path.name})")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("recording", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--gray", action="store_true", help="write 8-bit grayscale PNGs")
    p.add_argument("--mid-exposure", action="store_true",
                   help="timestamp frames at mid-exposure of the middle row instead of the start of exposure")
    p.add_argument("--uncalibrated-imu", action="store_true", help="use the uncalibrated (raw) accelerometer and gyroscope")
    args = p.parse_args()

    rec = args.recording
    meta = json.loads((rec / "metadata.json").read_text())
    rectified = meta["recording"]["view"] == "RECTIFIED"
    if rectified and meta["recording"]["stabilization"] != "OFF":
        print("warning: recorded with stabilization on; each frame is rotated by its stab_q in frames.csv", file=sys.stderr)
    offset, note = camera_clock_offset(rec, meta)
    print(note)

    mav = args.output / "mav0"
    for cam in meta["cameras"]:
        cam_dir = rec / cam["name"]
        out = mav / cam["name"]
        data = out / "data"
        data.mkdir(parents=True, exist_ok=True)
        frames = read_csv(cam_dir / "frames.csv")
        results = {r["timestamp_ns"]: r for r in read_csv(cam_dir / "capture_results.csv")}
        if rectified and len({(r["fx"], r["fy"], r["cx"], r["cy"]) for r in frames}) > 1:
            print(f"warning: {cam['name']}: zoom or convergence changed during the recording; sensor.yaml has the "
                  "starting intrinsics, frames.csv has each frame's", file=sys.stderr)

        def stamp(row):
            t = int(row["timestamp_ns"]) + offset
            if args.mid_exposure:
                r = results.get(row["timestamp_ns"])
                if r and r["exposure_time_ns"]:
                    t += int(r["exposure_time_ns"]) // 2 + int(r["rolling_shutter_skew_ns"] or 0) // 2
            return t

        with tempfile.TemporaryDirectory() as tmp:
            decoded = extract_frames(cam_dir, Path(tmp), args.gray)
            if decoded is not None and len(decoded) != len(frames):
                print(f"warning: {cam['name']}: {len(decoded)} video frames but {len(frames)} rows in frames.csv; "
                      "matching by order", file=sys.stderr)
            with open(out / "data.csv", "w") as f:
                f.write("#timestamp [ns],filename\n")
                for i, row in enumerate(frames):
                    name = f"{stamp(row)}.png"
                    if decoded is not None:
                        if i >= len(decoded):
                            break
                        shutil.move(str(decoded[i]), data / name)
                    else:
                        src = cam_dir / "data" / f"{row['timestamp_ns']}.jpg"
                        if not src.exists():
                            continue
                        cmd = ["ffmpeg", "-v", "error", "-y", "-i", str(src)]
                        if args.gray:
                            cmd += ["-pix_fmt", "gray"]
                        subprocess.run(cmd + [str(data / name)], check=True)
                    f.write(f"{stamp(row)},{name}\n")
        write_camera_yaml(out / "sensor.yaml", cam, meta, rectified)
        print(f"{cam['name']}: {len(list(data.iterdir()))} frames")

    imu = mav / "imu0"
    imu.mkdir(parents=True, exist_ok=True)
    convert_imu(rec, imu, args.uncalibrated_imu)
    if "stereo" in meta and meta["stereo"].get("baseline_m"):
        print(f"stereo baseline from lens poses: {meta['stereo']['baseline_m'] * 1000:.1f} mm")


if __name__ == "__main__":
    main()
