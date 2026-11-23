"""Sign in from the Android builder, publish as the owner and manage My games, against a local SpawnKit server (C11b).

Start the server in local mode first, with its console written to a log file, for example:
    SPAWNKIT_LOCAL=1 PORT=8080 HOST=127.0.0.1 SPAWNKIT_DATA_DIR=<dir> server/build/install/server/bin/server > server.log
Install the builder APK on the SpawnKit validation emulator. The emulator reaches the server through a private adb
tunnel, so this also works offline.
Usage: python tools/android-account-smoke.py <new-evidence-directory> <server-log> [--port 8080] [--serial emulator-5554]
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


def run(output, server_log, port, serial):
    if not serial.startswith("emulator-"):
        raise ValueError("Select a validation emulator.")
    adb = shutil.which("adb") or Path(os.environ["LOCALAPPDATA"], "Android/Sdk/platform-tools/adb.exe")
    output.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex[:8]
    dump = f"/data/local/tmp/spawnkit-account-{token}.xml"
    email = f"ada{token}@example.com"

    def device(*args, binary=False):
        result = subprocess.run([adb, "-s", serial, *args], capture_output=True, check=True, timeout=55)
        return result.stdout if binary else result.stdout.decode().strip()

    def nodes():
        for _ in range(8):
            try:
                if "dumped to:" in device("shell", "uiautomator", "dump", dump):
                    return ET.fromstring(device("exec-out", "cat", dump)).findall(".//node")
            except (subprocess.CalledProcessError, ET.ParseError):
                pass  # The tree is briefly unavailable while the UI animates.
            time.sleep(.5)
        raise RuntimeError("Android did not expose its accessibility tree.")

    def bounds(node):
        return list(map(int, re.findall(r"\d+", node.attrib["bounds"])))

    def tap_node(node):
        x1, y1, x2, y2 = bounds(node)
        device("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(.5)

    def label(node, text):
        return any(node.get(attr, "").casefold() == text.casefold() for attr in ("text", "content-desc"))

    def wait(predicate, name, tap=False, last=False):
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            matches = [n for n in nodes() if predicate(n)]
            if matches:
                node = max(matches, key=lambda n: bounds(n)[1]) if last else matches[0]
                if tap:
                    tap_node(node)
                print(name, flush=True)
                return node
            time.sleep(.5)
        raise RuntimeError(f"Timed out waiting for {name}")

    def scroll_to(text):
        for _ in range(8):
            snapshot = nodes()
            if any(label(n, text) for n in snapshot):
                return
            scroll = next(n for n in snapshot if n.get("scrollable") == "true")
            x1, y1, x2, y2 = bounds(scroll)
            x = str((x1 + x2) // 2)
            device("shell", "input", "swipe", x, str((y1 + 4 * y2) // 5), x, str((y1 + y2) // 2), "400")
            time.sleep(.4)
        raise RuntimeError(f"Could not scroll to {text}")

    def fields():
        return [n for n in nodes() if n.get("class") == "android.widget.EditText"]

    def input_text(node, value):
        tap_node(node)
        device("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
        device("shell", "input", "text", value.replace(" ", "%s"))
        device("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(.4)

    def capture(filename):
        (output / filename).write_bytes(device("exec-out", "screencap", "-p", binary=True))

    def console_code():
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            lines = [line for line in server_log.read_text(encoding="utf-8", errors="replace").splitlines() if f"To: {email}" in line]
            if lines:
                return re.search(r"code: (\d{6})", lines[-1]).group(1)
            time.sleep(.5)
        raise RuntimeError("The server printed no code; is it in local mode?")

    emulator = device("shell", "getprop", "ro.boot.qemu.avd_name")
    if not emulator.startswith("SpawnKit"):
        raise RuntimeError(f"Expected a SpawnKit validation emulator, found {emulator}")
    tunnel = int(device("reverse", "tcp:0", f"tcp:{port}"))
    address = f"http://127.0.0.1:{tunnel}"
    try:
        device("shell", "am", "start", "-n", "dev.spawnkit/.MainActivity")
        initial = nodes()
        restore = next((n for n in initial if label(n, "Restore draft")), None)
        if restore is not None:
            tap_node(restore)
        wait(lambda n: label(n, "Project"), "Project menu", tap=True)
        wait(lambda n: label(n, "Account…"), "Account", tap=True)
        wait(lambda n: label(n, "SpawnKit account"), "Account screen")
        input_text(fields()[0], address)
        wait(lambda n: label(n, "Create account"), "Create account tab", tap=True)
        boxes = fields()
        input_text(boxes[1], "Ada")
        input_text(fields()[2], email)
        input_text(fields()[3], "correct horse")
        wait(lambda n: label(n, "Create account"), "Create account button", tap=True, last=True)
        wait(lambda n: n.get("text", "").startswith("Check your email"), "Code requested")
        code = console_code()
        input_text(fields()[1], code)
        wait(lambda n: label(n, "Confirm"), "Confirm code", tap=True)
        wait(lambda n: n.get("text", "").startswith("Signed in as Ada"), "Signed in")
        capture("android-account-signed-in.png")

        wait(lambda n: label(n, "Back to editor"), "Back to editor", tap=True)
        wait(lambda n: label(n, "Project"), "Project menu", tap=True)
        wait(lambda n: label(n, "Release game…"), "Release game", tap=True)
        scroll_to("Publish as a new game")
        wait(lambda n: label(n, "Publish as a new game"), "Publish as a new game", tap=True)
        wait(lambda n: label(n, "Publish"), "Confirm publishing", tap=True)
        wait(lambda n: "/play/" in n.get("text", ""), "Play link")
        scroll_to("Unpublish")
        capture("android-account-my-games.png")
        wait(lambda n: label(n, "Unpublish"), "Unpublish", tap=True, last=True)
        # The confirmation is its own window, so its button is the only "Unpublish" in the dump.
        wait(lambda n: n.get("text", "").startswith("Players can no longer open it"), "Unpublish confirmation")
        wait(lambda n: label(n, "Unpublish"), "Confirm unpublishing", tap=True)
        wait(lambda n: n.get("text", "").startswith("Unpublished"), "Unpublished")
        capture("android-account-unpublished.png")
        print(f"PASS: {emulator} signed up with an emailed code, published as the owner and unpublished from My games.", flush=True)
    finally:
        device("reverse", "--remove", f"tcp:{tunnel}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("server_log", type=Path)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    run(args.output, args.server_log, args.port, args.serial)
