"""Build a C14 spawner with real Android taps, then check that template copies move during play.

Install the current debug APK on an emulator, then run:
    python tools/android-spawn-smoke.py docs/media
Needs Pillow. Starts a new blank project, leaving any existing saved project file alone.
"""
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image

sys.stdout.reconfigure(encoding="utf-8")

ADB = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.run([str(ADB), *args], check=True, capture_output=True).stdout


def nodes():
    for _ in range(5):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
            root = ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml"))
            found = []
            for node in root.iter("node"):
                match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if match:
                    x1, y1, x2, y2 = map(int, match.groups())
                    found.append((node.get("text", ""), node.get("content-desc", ""), (x1 + x2) // 2, (y1 + y2) // 2))
            if any(text or desc for text, desc, *_ in found):
                return found
        except (ET.ParseError, subprocess.CalledProcessError):
            pass
        time.sleep(1)
    raise RuntimeError("The Android accessibility tree did not become available")


def find(label):
    return next((n for n in nodes() if label in n[:2]), None)


def tap(label, scroll=False):
    for _ in range(12 if scroll else 1):
        node = find(label)
        if node:
            adb("shell", "input", "tap", str(node[2]), str(node[3]))
            time.sleep(0.5)
            print("tap", label, flush=True)
            return
        adb("shell", "input", "swipe", "540", "2000", "540", "1300", "350")
    raise RuntimeError(f"Could not find {label!r}: {nodes()}")


def capture(name):
    data = adb("exec-out", "screencap", "-p")
    (OUT / name).write_bytes(data)
    return Image.open(io.BytesIO(data)).convert("RGB")


def yellow_center(image):
    # Coin preset's original yellow (#ffc107), excluding the bottom editing controls.
    points = []
    for y in range(180, image.height - 250, 3):
        for x in range(20, image.width - 20, 3):
            r, g, b = image.getpixel((x, y))
            if abs(r - 255) < 15 and abs(g - 193) < 15 and abs(b - 7) < 15:
                points.append((x, y))
    if not points:
        raise RuntimeError("No spawned yellow coin is visible in the play canvas")
    return sum(y for _, y in points) / len(points)


adb("shell", "am", "force-stop", "dev.spawnkit")
adb("shell", "am", "start", "-W", "-n", "dev.spawnkit/.MainActivity")
deadline = time.time() + 120
while time.time() < deadline:
    if find("Start new"):
        tap("Start new")
        break
    if find("Project"):
        break
    time.sleep(1)
else:
    raise RuntimeError("The builder did not finish opening")
tap("Project")
tap("New game…")
tap("Blank")
if find("Discard changes"):
    tap("Discard changes")
tap("Coin")
tap("Object")
tap("+ Template", scroll=True)
tap("Rules")
tap("+ Add rule")
tap("Leaves the play area", scroll=True)
tap("Done")
tap("+ Do", scroll=True)
tap("Remove this object", scroll=True)
tap("Done")
tap("Add")
tap("Player")
tap("Rules")
tap("+ Add rule")
tap("Game starts")
tap("Done")
tap("+ Do", scroll=True)
tap("Spawn a copy", scroll=True)
tap("coin", scroll=True)
tap("down", scroll=True)
# Default speed is zero; the last + button in this dialog belongs to Speed.
for _ in range(2):
    plus = [n for n in nodes() if n[0] == "+"][-1]
    adb("shell", "input", "tap", str(plus[2]), str(plus[3]))
    time.sleep(0.3)
tap("Done")
capture("android-spawn-rules.png")
tap("▶ Play")
time.sleep(0.8)
first = yellow_center(capture("android-spawn-play.png"))
time.sleep(1)
second = yellow_center(capture("android-spawn-moving.png"))
assert second > first + 10, f"The spawned coin did not move down: {first} -> {second}"
print(f"OK: authored template spawned a moving copy ({first:.1f} -> {second:.1f})", flush=True)
