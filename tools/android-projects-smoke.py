"""C5 smoke test on a running emulator after android-rules-smoke.py has built its Score + collect game.

Uses the real Android document picker to save, start blank, reopen and play; then force-stops the app and restores
an autosaved edit. Usage: python -u tools/android-projects-smoke.py <capture-directory>.
Pass --resume-after-save only when an interrupted run already saved the sample and started a blank game.
"""
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)


def adb(*args, binary=False):
    result = subprocess.run([ADB, *args], capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/spawnkit-project-ui.xml")
    root = ET.fromstring(adb("exec-out", "cat", "/sdcard/spawnkit-project-ui.xml"))
    return list(root.iter("node"))


def find(label, exact=True):
    snapshot = nodes()
    # The viewport is described as "Scene" too; prefer the visible tab text over a fallback description.
    for attribute in ["text", "content-desc"]:
        for node in snapshot:
            value = node.get(attribute, "")
            if value == label if exact else label in value:
                return node
    return None


def wait_for(label, exact=True):
    for _ in range(10):
        node = find(label, exact)
        if node is not None:
            return node
        time.sleep(0.5)
    return None


def tap(label, exact=True):
    node = wait_for(label, exact)
    if node is None:
        raise RuntimeError(f"Could not find {label!r}: {[(n.get('text'), n.get('content-desc')) for n in nodes()]}")
    bounds = node.get("bounds").replace("][", ",").strip("[]").split(",")
    x1, y1, x2, y2 = map(int, bounds)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1)
    print("tap", label, flush=True)


def capture(name):
    with open(os.path.join(OUT, name), "wb") as output:
        output.write(adb("exec-out", "screencap", "-p", binary=True))
    print("capture", name, flush=True)


def menu(label):
    tap("Project", exact=False)
    tap(label, exact=False)


if "--resume-after-save" not in sys.argv[2:]:
    tap("Stop", exact=False)
    menu("Save")
    time.sleep(2)
    capture("android-project-picker.png")
    # The system provider chooses its default writable folder. The name comes from the workspace.
    tap("SAVE", exact=False)
    time.sleep(2)
    capture("android-project-saved.png")
    menu("New game")
tap("Scene", exact=True)
assert wait_for("Your scene is empty", exact=False) is not None
menu("Open project file")
time.sleep(2)
capture("android-project-open-picker.png")
tap("My game.spawnkit", exact=False)
time.sleep(2)
tap("Scene", exact=True)
assert all(wait_for(label) is not None for label in ["Coin", "Score", "Player"])
tap("Play", exact=False)
right = wait_for("Right")
assert right is not None
x1, y1, x2, y2 = map(int, right.get("bounds").replace("][", ",").strip("[]").split(","))
x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
adb("shell", "input", "swipe", x, y, x, y, "2500")
time.sleep(1)
capture("android-project-reopened-play.png")
tap("Stop", exact=False)
tap("Add", exact=True)
tap("Circle")
time.sleep(2)
adb("shell", "am", "force-stop", "dev.spawnkit")
adb("shell", "monkey", "-p", "dev.spawnkit", "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(4)
assert wait_for("Restore draft") is not None
capture("android-project-recovery.png")
tap("Restore draft")
tap("Scene", exact=True)
assert all(wait_for(label) is not None for label in ["Coin", "Score", "Player", "Circle"])
capture("android-project-restored.png")
print("PASS: document save/open, reopened rules, process-restart recovery", flush=True)
