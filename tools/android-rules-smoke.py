"""Drives the SpawnKit builder on a running Android emulator with real taps: builds Score + collect with rules, then
plays. Usage: install the debug APK, then `python tools/android-rules-smoke.py <screenshot-dir>`."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
OUT = sys.argv[1]


def adb(*args, binary=False):
    result = subprocess.run([ADB, *args], capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def nodes():
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        root = ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml"))
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
    for attempt in range(6):
        node = find(label, nth, exact, below)
        if node:
            adb("shell", "input", "tap", str(node[2]), str(node[3]))
            time.sleep(0.8)
            print("tap", label, node[2], node[3])
            return
        if not scroll:
            break
        # Scroll the bottom panel up a little and look again.
        adb("shell", "input", "swipe", "540", "2250", "540", "1950", "300")
        time.sleep(0.6)
    raise SystemExit(f"Could not find '{label}'")


def screenshot(name):
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(data)
    print("screenshot", name)


adb("shell", "am", "force-stop", "dev.spawnkit")
adb("shell", "monkey", "-p", "dev.spawnkit", "-c", "android.intent.category.LAUNCHER", "1")
for _ in range(10):
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

tap("Score", scroll=False)
tap("Coin", scroll=False)
tap("Object", scroll=False)
# The first "+" stepper in the inspector is the Transform's x.
for _ in range(6):
    tap("+", scroll=False)
tap("Add", scroll=False)
tap("Player", scroll=False)

tap("Rules", scroll=False)
tap("+ Add rule")
tap("Holding a key", scroll=False)
tap("Right", scroll=False)
tap("Done", scroll=False)
tap("+ Do")
tap("Move", scroll=False)
tap("Done", scroll=False)

tap("Scene", scroll=False, below=1400)
tap("Coin", scroll=False, below=1500)
tap("Rules", scroll=False)
tap("+ Add rule")
tap("Touching", scroll=False)
tap("player", scroll=False)
tap("Done", scroll=False)
for kind, target in [("Add to a number", "score"), ("Show a number", "score"), ("Remove this object", None)]:
    tap("+ Do")
    tap(kind, scroll=False)
    if target:
        tap(target, scroll=False)
    tap("Done", scroll=False)
screenshot("android-rules-edit.png")

tap("Play", exact=False, scroll=False)
time.sleep(1)
right = find("Right")
adb("shell", "input", "swipe", str(right[2]), str(right[3]), str(right[2]), str(right[3]), "2500")
time.sleep(1)
screenshot("android-rules-play.png")
score = [n for n in nodes() if "Score" in n[0]]
print("score nodes", score)
