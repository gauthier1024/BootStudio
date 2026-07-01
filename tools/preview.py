#!/usr/bin/env python3

import os
import sys
import shutil
import tempfile
import zipfile
import subprocess
from pathlib import Path

# --------------------------------------------------
# Colors
# --------------------------------------------------

CYAN = "\033[1;36m"
YELLOW = "\033[1;33m"
GREEN = "\033[1;32m"
RED = "\033[1;31m"
RESET = "\033[0m"

os.system("clear")

print(CYAN)
print("╔══════════════════════════════════════════════╗")
print("║         Bootanimation → MP4 Preview         ║")
print("╚══════════════════════════════════════════════╝")
print(RESET)

# --------------------------------------------------
# Input
# --------------------------------------------------

zip_file = input(f"{YELLOW}📦 Bootanimation ZIP : {RESET}").strip()

if not os.path.isfile(zip_file):
    print(f"\n{RED}[!] ZIP not found{RESET}")
    sys.exit(1)

name = Path(zip_file).stem

tmp = tempfile.mkdtemp()

extract_dir = os.path.join(tmp, "extract")
frames_dir = os.path.join(tmp, "frames")

os.makedirs(extract_dir)
os.makedirs(frames_dir)

try:

    # --------------------------------------------------
    # Extract ZIP
    # --------------------------------------------------

    print()
    print("──────────────────────────────────────────────")
    print("[1/4] Extracting bootanimation...")
    print("──────────────────────────────────────────────")

    with zipfile.ZipFile(zip_file, "r") as z:
        z.extractall(extract_dir)

    desc = os.path.join(extract_dir, "desc.txt")

    if not os.path.isfile(desc):
        print(f"\n{RED}[!] desc.txt missing{RESET}")
        sys.exit(1)

    # --------------------------------------------------
    # Read desc.txt
    # --------------------------------------------------

    with open(desc) as f:
        lines = [x.strip() for x in f if x.strip()]

    fps = lines[0].split()[2]

    parts = []

    for line in lines[1:]:
        split = line.split()

        if len(split) >= 4 and split[0] in ("p", "c"):
            parts.append(split[3])

    print(f"\nFPS : {fps}")

    # --------------------------------------------------
    # Extract frames
    # --------------------------------------------------

    print()
    print("──────────────────────────────────────────────")
    print("[2/4] Extracting frames...")
    print("──────────────────────────────────────────────")

    index = 0

    valid_ext = (
        ".png",
        ".jpg",
        ".jpeg",
        ".bmp",
        ".webp"
    )

    for part in parts:

        folder = os.path.join(extract_dir, part)

        if not os.path.isdir(folder):
            print(f"⚠ Missing {part}")
            continue

        print(f"→ {part}")

        files = sorted(os.listdir(folder))

        for file in files:

            if not file.lower().endswith(valid_ext):
                continue

            src = os.path.join(folder, file)
            dst = os.path.join(frames_dir, f"{index:06d}.jpg")

            subprocess.run([
                "ffmpeg",
                "-y",
                "-loglevel", "error",
                "-i", src,
                "-q:v", "12",
                dst
            ])

            index += 1

    if index == 0:
        print(f"\n{RED}[!] No frames found{RESET}")
        sys.exit(1)

    print(f"\nFrames extracted : {index}")

    # --------------------------------------------------
    # Create MP4 H264
    # --------------------------------------------------

    print()
    print("──────────────────────────────────────────────")
    print("[3/4] Creating MP4 H264...")
    print("──────────────────────────────────────────────")

    mp4 = f"{name}.mp4"

    if os.path.exists(mp4):
        os.remove(mp4)

    subprocess.run([
        "ffmpeg",
        "-y",
        "-loglevel", "error",
        "-framerate", fps,
        "-i", os.path.join(frames_dir, "%06d.jpg"),
        "-vf",
        "fps=15,"
        "crop=min(iw\\,ih):min(iw\\,ih):(iw-min(iw\\,ih))/2:(ih-min(iw\\,ih))/2,"
        "scale=256:256,"
        "setpts=PTS/4",
        "-c:v",
        "libx264",
        "-preset",
        "medium",
        "-crf",
        "23",
        "-pix_fmt",
        "yuv420p",
        "-movflags",
        "+faststart",
        mp4
    ], check=True)


    # --------------------------------------------------
    # Result
    # --------------------------------------------------

    print()
    print("──────────────────────────────────────────────")
    print("[4/4] Finished")
    print("──────────────────────────────────────────────")

    if os.path.isfile(mp4) and os.path.getsize(mp4) > 0:

        size = os.path.getsize(mp4)

        units = ["B", "KB", "MB", "GB"]
        i = 0

        while size >= 1024 and i < len(units) - 1:
            size /= 1024
            i += 1

        print()
        print(GREEN)
        print("╔══════════════════════════════════════════════╗")
        print("║            ✓ MP4 H264 Created               ║")
        print("╚══════════════════════════════════════════════╝")
        print(RESET)

        print(f"🎞 File : {mp4}")
        print(f"💾 Size : {size:.2f} {units[i]}")

    else:

        print(f"\n{RED}[!] MP4 creation failed{RESET}")

finally:
    shutil.rmtree(tmp, ignore_errors=True)
