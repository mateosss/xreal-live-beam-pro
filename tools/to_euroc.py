#!/usr/bin/env python3
"""Turn a video recording into a complete EuRoC dataset by extracting its frames.

    python3 tools/to_euroc.py RECORDING_DIR [--gray] [--keep-video]

Recordings are already in the EuRoC MAV layout (RECORDING_DIR/mav0/cam0, cam1, imu0 with
data.csv and sensor.yaml). Image recordings (PNG or JPEG, and all glasses recordings) are
complete as they are. Video recordings store each camera as mav0/camN/data.mp4 instead of
mav0/camN/data/; this script decodes it into mav0/camN/data/<timestamp>.png, matching frame i
of the video to row i of data.csv. Needs Python 3 and ffmpeg.

Recordings from app versions before 0.8.0 (format_version 1, no mav0/) need the previous
version of this script: git show v0.7.0:tools/to_euroc.py
"""
import argparse
import csv
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def extract(cam_dir, gray, keep_video):
    video = cam_dir / "data.mp4"
    if not video.exists():
        print(f"{cam_dir.name}: no data.mp4, images are already in data/")
        return
    with open(cam_dir / "data.csv", newline="") as f:
        rows = [r for r in csv.reader(f) if r and not r[0].startswith("#")]
    data = cam_dir / "data"
    data.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=cam_dir) as tmp:
        cmd = ["ffmpeg", "-v", "error", "-i", str(video), "-vsync", "0"]
        if gray:
            cmd += ["-pix_fmt", "gray"]
        subprocess.run(cmd + [str(Path(tmp) / "%08d.png")], check=True)
        decoded = sorted(Path(tmp).glob("*.png"))
        if len(decoded) != len(rows):
            print(f"warning: {cam_dir.name}: {len(decoded)} video frames but {len(rows)} rows in data.csv; "
                  "matching by order", file=sys.stderr)
        for frame, row in zip(decoded, rows):
            shutil.move(str(frame), data / row[1].strip())
    if not keep_video:
        video.unlink()
    print(f"{cam_dir.name}: {min(len(decoded), len(rows))} frames extracted")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("recording", type=Path)
    p.add_argument("--gray", action="store_true", help="write 8-bit grayscale PNGs, as EuRoC does")
    p.add_argument("--keep-video", action="store_true", help="keep data.mp4 after extracting it")
    args = p.parse_args()

    meta = json.loads((args.recording / "metadata.json").read_text())
    if meta.get("format_version", 1) < 2:
        sys.exit("This recording predates the EuRoC layout; convert it with: git show v0.7.0:tools/to_euroc.py")
    mav = args.recording / "mav0"
    for cam_dir in sorted(mav.glob("cam*")):
        extract(cam_dir, args.gray, args.keep_video)
    if not (mav / "imu0" / "data.csv").exists():
        print("note: no mav0/imu0 in this recording (IMU not recorded or not taken over)", file=sys.stderr)


if __name__ == "__main__":
    main()
