"""Drives the SpawnKit builder on a running Android emulator with real taps (C13b), signed out: opens Project → New
game…, checks "Describe your game…" with Sign in, taps Watch the AI build a game, follows the labelled replay until
Jungle Banana Run is applied (the canvas previewing it meanwhile), then reverts it and checks the game is blank again.
Usage: install the debug APK, then `python tools/android-ai-first-smoke.py <screenshot-dir>`."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
OUT = sys.argv[1]
BUTTON = (0x43, 0xA0, 0x47)  # the title screen's Play button
PLAYER = (0x21, 0x96, 0xF3)  # the Player preset
COIN = (0xFF, 0xC1, 0x07)  # the Coin preset


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



adb("shell", "am", "force-stop", "dev.spawnkit")
adb("shell", "monkey", "-p", "dev.spawnkit", "-c", "android.intent.category.LAUNCHER", "1")
for _ in range(15):
    start_new = find("Start new", exact=False)
    if start_new:
        adb("shell", "input", "tap", str(start_new[2]), str(start_new[3]))
        time.sleep(1)
        print("tap Start new", flush=True)
        break
    if find("Project"):
        break
    time.sleep(1)
else:
    raise SystemExit("The builder did not finish opening")

# The assistant is the first tab and opens first (C13b).
wait_for("Describe what to build or change", seconds=20)
screenshot("android-ai-first-chat.png")
tap("Project", scroll=False)
tap("New game…", scroll=False)
wait_for("Describe your game")
time.sleep(1)
screenshot("android-ai-first-new-game.png")
if not find("Sign in") or not find("Building with AI needs a free SpawnKit account", exact=False):
    raise SystemExit("Signed out, New game should explain that building with AI needs an account")
tap("Watch the AI build a game", scroll=False)
wait_for("Replay: claude-sonnet-5-5", seconds=20)
time.sleep(3)
screenshot("android-ai-first-replay-running.png")
# The canvas draws the staged game as it grows, labelled as a preview (C13b).
wait_for("Preview of the assistant's changes", seconds=20)
# Keep the latest picture taken while the preview still shows, so it has as much of the game as possible.
for _ in range(12):
    screenshot("android-ai-first-replay-preview-next.png")
    if not find("Preview of the assistant's changes", exact=False):
        break
    os.replace(os.path.join(OUT, "android-ai-first-replay-preview-next.png"), os.path.join(OUT, "android-ai-first-replay-preview.png"))
    time.sleep(0.5)
# The card's heading can scroll above its long summary; its steps button stays in view.
wait_for("Show 142 changes", seconds=120)
time.sleep(1)
screenshot("android-ai-first-replay-applied.png")
if not find("7 objects in Title", exact=False):
    raise SystemExit("The replay should open on Jungle Banana Run's title screen")
tap("Revert", scroll=False)
wait_for("Reverted", seconds=10)
time.sleep(1)
screenshot("android-ai-first-reverted.png")
if not find("0 objects in Main scene", exact=False):
    raise SystemExit("Revert should bring back the blank game")
print("OK: signed out, New game played the labelled whole-game replay, and Revert undid it")
