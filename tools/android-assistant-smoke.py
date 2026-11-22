"""Drives the SpawnKit assistant on a running Android emulator with real taps (C7c). It uses the labelled scripted demo:
no AI model is called and no key is needed. The script adds a Player, a Coin and a Score, asks for the collect rule,
reviews and accepts it, asks for arrow-key movement, accepts, then plays to "Score: 1".
Usage: install the debug APK, then `python tools/android-assistant-smoke.py <screenshot-dir>`."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
OUT = sys.argv[1]
COLLECT = "When the player touches a coin, add 1 to the score and remove the coin."
MOVE = "Make the player move with the arrow keys."


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


def ask(prompt, staged):
    tap(prompt)
    tap("Ask")
    wait_for(staged)


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

tap("Score", scroll=False)
tap("Coin", scroll=False)
tap("Object", scroll=False)
# The first "+" stepper in the inspector is the Transform's x: move the coin to the right of the player.
for _ in range(6):
    tap("+", scroll=False)
tap("Add", scroll=False)
tap("Player", scroll=False)

tap("AI", scroll=False)
ask(COLLECT, "Add a rule to Coin")
time.sleep(1)
screenshot("android-assistant-review.png")
tap("Accept")
ask(MOVE, "Add a rule to Player")
tap("Accept")
time.sleep(1)
screenshot("android-assistant-accepted.png")

tap("Play", exact=False, scroll=False)
time.sleep(1)
right = find("Right")
adb("shell", "input", "swipe", str(right[2]), str(right[3]), str(right[2]), str(right[3]), "2500")
time.sleep(1)
screenshot("android-assistant-play.png")
# Game text is drawn on the canvas, not exposed as accessibility nodes: the score is checked in the play screenshot.
print("OK: assistant flow completed; check android-assistant-play.png for 'Score: 1'")
