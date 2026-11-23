"""Open each C17 complete-game template from New game with real Android taps, play its title and start the game.

Install the current debug APK on an emulator, then run:
    python tools/android-templates-smoke.py docs/media
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


def near(color, target):
    return all(abs(c - t) < 14 for c, t in zip(color, target))


TEMPLATES = [
    ("Toaster Tycoon", "toaster", (0x4E, 0x34, 0x2E)),
    ("Sky Hopper", "sky", (0x81, 0xD4, 0xFA)),
    ("Star Defender", "star", (0x0B, 0x10, 0x26)),
]

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
for name, short, level_color in TEMPLATES:
    tap("Project")
    tap("New game…")
    time.sleep(1)
    if short == "toaster":
        capture("android-templates-picker.png")
    tap(name, scroll=True)
    if find("Discard changes"):
        tap("Discard changes")
    time.sleep(1)
    tap("▶ Play")
    time.sleep(1.5)
    title = capture(f"android-template-{short}-title.png")
    runs = green_runs(title)
    assert len(runs) == 4, f"{name}: expected Play, Levels, Shop and How it works, found {runs}"
    play = runs[0]
    adb("shell", "input", "tap", str(1080 * 30 // 100), str((play[0] + play[1]) // 2))
    time.sleep(1.5)
    if short == "sky":
        right = find("Right")
        adb("shell", "input", "swipe", str(right[2]), str(right[3]), str(right[2]), str(right[3]), "1200")
        time.sleep(0.3)
    level = capture(f"android-template-{short}-play.png")
    color = level.getpixel((60, 1200))
    assert near(color, level_color), f"{name}: the first level did not open ({color})"
    print(f"OK: {name} opened from New game, played its title and started the game", flush=True)
    tap("■ Stop")
    time.sleep(1)
