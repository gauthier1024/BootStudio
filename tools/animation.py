#!/usr/bin/env python3

import os
import shutil
import subprocess
import sys
import zipfile

# ----------------------------
# Colors
# ----------------------------
CYAN = "\033[1;36m"
YELLOW = "\033[1;33m"
GREEN = "\033[1;32m"
RED = "\033[1;31m"
RESET = "\033[0m"

# ----------------------------
# Banner
# ----------------------------
os.system("clear")

print(CYAN)
print("╔══════════════════════════════════════════════╗")
print("║      Video → Android Bootanimation Creator  ║")
print("╚══════════════════════════════════════════════╝")
print(RESET)

# ----------------------------
# Inputs
# ----------------------------
video = input(f"{YELLOW}📹 Input video : {RESET}").strip()

if not os.path.isfile(video):
    print(f"\n{RED}[!] File not found{RESET}")
    sys.exit(1)

width = input(f"{YELLOW}📏 Width       : {RESET}").strip()
height = input(f"{YELLOW}📐 Height      : {RESET}").strip()
fps = input(f"{YELLOW}🎞 FPS         : {RESET}").strip()

# ----------------------------
# Paths
# ----------------------------
WORKDIR = "bootanimation_build"
PART0 = os.path.join(WORKDIR, "part0")
ZIP = "bootanimation.zip"

# ----------------------------
# Cleanup previous files
# ----------------------------
if os.path.exists(WORKDIR):
    shutil.rmtree(WORKDIR)

if os.path.exists(ZIP):
    os.remove(ZIP)

os.makedirs(PART0)

print()
print("──────────────────────────────────────────────")
print("[1/3] Converting video and extracting frames...")
print("──────────────────────────────────────────────")

try:
    subprocess.run([
        "ffmpeg",
        "-hide_banner",
        "-loglevel", "error",
        "-y",
        "-i", video,
        "-vf", f"fps={fps},scale={width}:{height}:flags=lanczos",
        os.path.join(PART0, "%05d.png")
    ], check=True)

except subprocess.CalledProcessError:
    print(f"\n{RED}[!] FFmpeg conversion failed.{RESET}")
    shutil.rmtree(WORKDIR)
    sys.exit(1)

frames = sorted(os.listdir(PART0))

if len(frames) == 0:
    print(f"\n{RED}[!] No frames extracted.{RESET}")
    shutil.rmtree(WORKDIR)
    sys.exit(1)

print()
print("──────────────────────────────────────────────")
print("[2/3] Creating desc.txt...")
print("──────────────────────────────────────────────")

with open(os.path.join(WORKDIR, "desc.txt"), "w") as f:
    f.write(f"{width} {height} {fps}\n")
    f.write("p 0 0 part0\n")

print()
print("──────────────────────────────────────────────")
print("[3/3] Creating bootanimation.zip...")
print("──────────────────────────────────────────────")

with zipfile.ZipFile(ZIP, "w", compression=zipfile.ZIP_STORED) as z:

    z.write(
        os.path.join(WORKDIR, "desc.txt"),
        "desc.txt"
    )

    for frame in frames:
        z.write(
            os.path.join(PART0, frame),
            os.path.join("part0", frame)
        )

# ----------------------------
# Stats
# ----------------------------
size = os.path.getsize(ZIP)

units = ["B", "KB", "MB", "GB"]
i = 0
while size >= 1024 and i < len(units) - 1:
    size /= 1024
    i += 1

size_str = f"{size:.2f} {units[i]}"

# Cleanup
shutil.rmtree(WORKDIR)

# ----------------------------
# Done
# ----------------------------
print()
print(GREEN)
print("╔══════════════════════════════════════════════╗")
print("║          ✓ Bootanimation Created            ║")
print("╚══════════════════════════════════════════════╝")
print(RESET)

print(f"📦 File        : {ZIP}")
print(f"🖼 Frames      : {len(frames)}")
print(f"📏 Resolution  : {width}x{height}")
print(f"🎞 FPS         : {fps}")
print(f"💾 Size        : {size_str}")

print()
