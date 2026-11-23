"""Build a C14b platformer with real Android taps, then check that the player stands on the platform and jumps.

Install the current debug APK on an emulator, then run:
    python tools/android-physics-smoke.py docs/media
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


def rows(image, color):
    """Rows of the play canvas holding the palette color, excluding the top bar and the bottom controls."""
    found = []
    for y in range(180, image.height - 450, 2):
        for x in range(20, image.width - 20, 4):
            r, g, b = image.getpixel((x, y))
            if abs(r - color[0]) < 12 and abs(g - color[1]) < 12 and abs(b - color[2]) < 12:
                found.append(y)
                break
    if not found:
        raise RuntimeError(f"No {color} pixels are visible in the play canvas")
    return found


def gap(image):
    """Pixels between the player's bottom edge (#2196f3) and the platform's top edge (#8d6e63)."""
    return min(rows(image, (0x8D, 0x6E, 0x63))) - max(rows(image, (0x21, 0x96, 0xF3)))


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
tap("Platform")
tap("Player")
tap("Object")
tap("+ Body", scroll=True)
tap("Rules")
tap("+ Add rule")
tap("Pressing a key", scroll=True)
tap("Done")
tap("+ Do", scroll=True)
tap("Jump", scroll=True)
tap("Done")
capture("android-physics-rules.png")
tap("Add")
tap("Camera")
tap("Object")
tap("+ Follow", scroll=True)
tap("▶ Play")
time.sleep(1.5)
resting = gap(capture("android-physics-play.png"))
assert abs(resting) <= 6, f"The player is not standing on the platform: gap {resting}px"
jump = find("Jump")
adb("shell", "input", "tap", str(jump[2]), str(jump[3]))
time.sleep(0.15)
jumping = gap(capture("android-physics-jump.png"))
assert jumping > resting + 40, f"The player did not jump: gap {resting}px -> {jumping}px"
print(f"OK: the player stood on the platform (gap {resting}px) and jumped (gap {jumping}px)", flush=True)
