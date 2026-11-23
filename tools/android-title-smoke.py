"""Drives the SpawnKit builder on a running Android emulator with real taps (C9): adds a Player, adds a title screen
from the scene list, plays, taps the title's Play button on the game canvas and checks that the level is shown.
Usage: install the debug APK, then `python tools/android-title-smoke.py <screenshot-dir>`. Needs Pillow."""
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from PIL import Image

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
OUT = sys.argv[1]
BUTTON = (0x43, 0xA0, 0x47)  # the title screen's Play button
PLAYER = (0x21, 0x96, 0xF3)  # the Player preset


def adb(*args, binary=False):
    result = subprocess.run([ADB, *args], capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def nodes():
    found = []
    for _ in range(8):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
            root = ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml"))
        except (ET.ParseError, subprocess.CalledProcessError):
            # The app is still starting or animating; the dump is not ready yet.
            time.sleep(1)
            continue
        found = []
        for node in root.iter("node"):
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if bounds:
                x1, y1, x2, y2 = map(int, bounds.groups())
                found.append((node.get("text", ""), node.get("content-desc", ""), (x1 + x2) // 2, (y1 + y2) // 2, y1))
        if any(text or desc for text, desc, *_ in found):
            return found
        time.sleep(0.3)
    return found


def find(label, nth=0, exact=True, below=0):
    matches = [n for n in nodes() if n[4] >= below and ((n[0] == label or n[1] == label) if exact else (label in n[0] or label in n[1]))]
    return matches[nth] if len(matches) > nth else None


def tap(label, nth=0, exact=True, scroll=True, below=0):
    for _ in range(8):
        node = find(label, nth, exact, below)
        if node:
            adb("shell", "input", "tap", str(node[2]), str(node[3]))
            time.sleep(0.8)
            print("tap", label, node[2], node[3], flush=True)
            return
        if not scroll:
            break
        adb("shell", "input", "swipe", "540", "2250", "540", "1950", "300")
        time.sleep(0.6)
    raise SystemExit(f"Could not find '{label}'")


def wait_for(text, seconds=30):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if find(text, exact=False):
            return
        time.sleep(1)
    raise SystemExit(f"'{text}' did not appear")


def screenshot(name):
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(data)
    print("screenshot", name, flush=True)


def pixels_like(name, color, tolerance=12):
    """Centre of the pixels close to [color] in screenshot [name], and how many there are."""
    image = Image.open(os.path.join(OUT, name)).convert("RGB")
    width, height = image.size
    xs, ys = [], []
    data = image.load()
    for y in range(0, height, 4):
        for x in range(0, width, 4):
            r, g, b = data[x, y]
            if abs(r - color[0]) <= tolerance and abs(g - color[1]) <= tolerance and abs(b - color[2]) <= tolerance:
                xs.append(x)
                ys.append(y)
    if not xs:
        return None, 0
    return (sum(xs) // len(xs), sum(ys) // len(ys)), len(xs)


adb("shell", "am", "force-stop", "dev.spawnkit")
adb("shell", "monkey", "-p", "dev.spawnkit", "-c", "android.intent.category.LAUNCHER", "1")
for _ in range(15):
    start_new = find("Start new", exact=False)
    if start_new:
        adb("shell", "input", "tap", str(start_new[2]), str(start_new[3]))
        time.sleep(1)
        print("tap Start new", flush=True)
        break
    if find("Score"):
        break
    time.sleep(1)
else:
    raise SystemExit("The builder did not finish opening")

tap("Player", scroll=False)
tap("Scene", scroll=False, below=1400)
tap("+ Title screen")
tap("Play")  # the Play-only layout
time.sleep(1)
screenshot("android-title-edit.png")
tap("Play", exact=False, scroll=False)
time.sleep(1.5)
screenshot("android-title-play.png")
centre, count = pixels_like("android-title-play.png", BUTTON)
if centre is None:
    raise SystemExit("The title screen's Play button is not on screen")
print("Play button at", centre, "pixels", count, flush=True)
adb("shell", "input", "tap", str(centre[0]), str(centre[1]))
time.sleep(1.5)
screenshot("android-title-level.png")
_, button_left = pixels_like("android-title-level.png", BUTTON)
_, player = pixels_like("android-title-level.png", PLAYER)
print("after tap: button pixels", button_left, "player pixels", player, flush=True)
if button_left > 0 or player == 0:
    raise SystemExit("Tapping Play did not reach the level")
print("OK: the title screen led to the level")
