"""Build a C15 home screen with real Android taps, then play it and open the Levels scene from its button.

Install the current debug APK on an emulator, then run:
    python tools/android-home-smoke.py docs/media
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


def find(label, below=0):
    return next((n for n in nodes() if label in n[:2] and n[3] > below), None)


def tap(label, scroll=False, below=0):
    for _ in range(12 if scroll else 1):
        node = find(label, below)
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


def green_runs(image):
    """Vertical runs of button green (#43a047) left of the labels in the play canvas, top to bottom."""
    x = image.width * 30 // 100
    runs, start = [], None
    for y in range(180, image.height - 300):
        r, g, b = image.getpixel((x, y))
        green = abs(r - 0x43) < 12 and abs(g - 0xA0) < 12 and abs(b - 0x47) < 12
        if green and start is None:
            start = y
        if not green and start is not None:
            if y - start > 20:
                runs.append((start, y))
            start = None
    return runs


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
# The viewport's description is also "Scene": take the tab under it.
tap("Scene", below=1400)
tap("+ Title screen", scroll=True)
tap("Play + Levels + Shop + How it works")
tap("Add")
tap("Text")
tap("Object")
tap("Move into…")
tap("Play button", scroll=True)
assert find("Inside Play button"), "The text was not moved into the Play button"
capture("android-home-edit.png")
tap("▶ Play")
time.sleep(1.5)
runs = green_runs(capture("android-home-play.png"))
assert len(runs) == 4, f"Expected four buttons on the home screen, found {runs}"
levels = runs[1]
adb("shell", "input", "tap", str(1080 * 30 // 100), str((levels[0] + levels[1]) // 2))
time.sleep(1)
after = capture("android-home-levels.png")
r, g, b = after.getpixel((60, 1200))
assert (abs(r - 0x00) < 12 and abs(g - 0x4D) < 12 and abs(b - 0x40) < 12), f"The Levels scene did not open: {(r, g, b)}"
print("OK: built the full home screen, grouped a text into Play, and opened Levels from its button", flush=True)
