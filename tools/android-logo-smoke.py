"""Put an imported logo on a C15 home screen with real Android taps, through the system picture picker, then play it.

Install the current debug APK on an emulator, then run:
    python tools/android-logo-smoke.py docs/media tools/fixtures/spawnkit-logo.png
The logo is pushed to the emulator's Pictures folder and indexed, so the system picker lists it.
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
LOGO = Path(sys.argv[2])
os.environ["MSYS_NO_PATHCONV"] = "1"
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


def count(image, color, tolerance=10):
    """Pixels of [color] in the play canvas, excluding the top bar and the bottom controls."""
    found = 0
    for y in range(200, image.height - 500, 3):
        for x in range(0, image.width, 3):
            r, g, b = image.getpixel((x, y))
            if abs(r - color[0]) < tolerance and abs(g - color[1]) < tolerance and abs(b - color[2]) < tolerance:
                found += 1
    return found


adb("push", str(LOGO), "/sdcard/Pictures/spawnkit-logo.png")
adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Pictures/spawnkit-logo.png")
time.sleep(2)
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
tap("Play")
tap("Add")
tap("Image…", scroll=True)
time.sleep(2)
capture("android-logo-picker.png")
# The system photo picker or the documents picker, whichever this Android version shows.
for _ in range(10):
    tree = nodes()
    logo = next((n for n in tree if "spawnkit-logo" in n[0] or "spawnkit-logo" in n[1]), None)
    photo = next((n for n in tree if n[1].startswith("Photo taken") or n[1].startswith("Photo")), None)
    target = logo or photo
    if target:
        adb("shell", "input", "tap", str(target[2]), str(target[3]))
        print("picked", target[:2], flush=True)
        break
    time.sleep(1)
else:
    raise RuntimeError(f"The picker did not show the logo: {nodes()}")
time.sleep(2)
deadline = time.time() + 20
# The photo picker hides file names, so the image may be called "Picture".
while time.time() < deadline and not (find("spawnkit-logo") or find("Picture")):
    time.sleep(1)
assert find("spawnkit-logo") or find("Picture"), f"The logo was not imported: {nodes()}"
capture("android-logo-edit.png")
tap("▶ Play")
time.sleep(1.5)
shot = capture("android-logo-play.png")
yellow = count(shot, (0xFF, 0xC1, 0x07))
assert yellow > 500, f"The logo is not drawn on the home screen ({yellow} logo pixels)"
print(f"OK: imported the logo through the system picker and played the home screen with it ({yellow} logo pixels)", flush=True)
