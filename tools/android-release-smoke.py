"""Export the current validation game from Android through SAF, then play the exported ZIP in Chrome.

Install the builder APK on the SpawnKit validation emulator and open the Score + collect workspace
from android-projects-smoke.py. Requires the same Python dependencies as release-player-smoke.py.
Usage: python tools/android-release-smoke.py <new-evidence-directory> [--serial emulator-5554]
"""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile


def run(output, serial, arcade_port, publish_only):
    if not serial.startswith("emulator-"):
        raise ValueError("Select a validation emulator.")
    adb = shutil.which("adb") or Path(os.environ["LOCALAPPDATA"], "Android/Sdk/platform-tools/adb.exe")
    output.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex[:10]
    dump = f"/data/local/tmp/spawnkit-release-{token}.xml"
    basename = f"Coin-chase-{token}.zip"

    def device(*args, binary=False):
        result = subprocess.run([adb, "-s", serial, *args], capture_output=True, check=True, timeout=55)
        return result.stdout if binary else result.stdout.decode().strip()

    def nodes():
        for _ in range(5):
            if "dumped to:" in device("shell", "uiautomator", "dump", dump):
                return ET.fromstring(device("exec-out", "cat", dump)).findall(".//node")
            time.sleep(.5)
        raise RuntimeError("Android did not expose its accessibility tree.")

    def tap_node(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        device("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def label(node, text):
        return any(node.get(attr, "").casefold() == text.casefold() for attr in ("text", "content-desc"))

    def wait(predicate, name, tap=False):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            snapshot = nodes()
            node = next((n for n in snapshot if predicate(n)), None)
            if node is not None:
                if tap:
                    tap_node(node)
                    time.sleep(.4)
                print(name, flush=True)
                return node
            time.sleep(.5)
        raise RuntimeError(f"Timed out waiting for {name}")

    def capture(filename):
        (output / filename).write_bytes(device("exec-out", "screencap", "-p", binary=True))

    def input_text(node, value):
        tap_node(node)
        device("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
        device("shell", "input", "text", value)
        device("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(.4)

    emulator = device("shell", "getprop", "ro.boot.qemu.avd_name")
    if not emulator.startswith("SpawnKit"):
        raise RuntimeError(f"Expected a SpawnKit validation emulator, found {emulator}")
    device("shell", "am", "start", "-n", "dev.spawnkit/.MainActivity")
    initial = nodes()
    if not any(label(n, "Back to editor") for n in initial):
        restore = next((n for n in initial if label(n, "Restore draft")), None)
        if restore is not None:
            tap_node(restore)
            initial = nodes()
        stop = next((n for n in initial if n.get("text", "").endswith("Stop")), None)
        if stop is not None:
            tap_node(stop)
        wait(lambda n: label(n, "Project"), "Project menu", tap=True)
        wait(lambda n: label(n, "Release game…"), "Release game", tap=True)
        title = wait(lambda n: n.get("class") == "android.widget.EditText", "Release title")
        input_text(title, "Coin%schase")

    def publish_to(address):
        for _ in range(6):
            snapshot = nodes()
            fields = [n for n in snapshot if n.get("class") == "android.widget.EditText"]
            if fields and any(label(n, "Arcade server address") for n in snapshot):
                break
            scroll = next(n for n in snapshot if n.get("scrollable") == "true")
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", scroll.attrib["bounds"]))
            x = str((x1 + x2) // 2)
            device("shell", "input", "swipe", x, str((y1 + 4 * y2) // 5), x, str((y1 + y2) // 2), "400")
        else:
            raise RuntimeError("Could not reach the Arcade address field")
        input_text(fields[-1], address)
        wait(lambda n: label(n, "Publish game"), "Publish game", tap=True)
        node = wait(lambda n: n.get("text", "").startswith(address + "/play/"), "Published play URL")
        play_path = node.attrib["text"].removeprefix(address)
        capture("android-arcade-published.png")
        subprocess.run([sys.executable, str(Path(__file__).with_name("release-player-smoke.py")),
                        f"http://127.0.0.1:{arcade_port}{play_path}", str(output / "android-arcade-playing.png"), "--move-right"], check=True)

    def publish():
        # A private adb tunnel also works on an offline validation AVD. Let adb choose a free device port.
        port = int(device("reverse", "tcp:0", f"tcp:{arcade_port}"))
        try:
            publish_to(f"http://127.0.0.1:{port}")
        finally:
            device("reverse", "--remove", f"tcp:{port}")

    if publish_only:
        publish()
        return
    capture("android-release-manager.png")
    wait(lambda n: label(n, "Save web ZIP"), "Save web ZIP", tap=True)
    field = wait(lambda n: n.get("class") == "android.widget.EditText" and "documentsui" in n.get("package", ""), "Android save picker")
    snapshot = nodes()
    if not any(label(n, "Downloads") for n in snapshot):
        wait(lambda n: label(n, "Show roots") or label(n, "Show navigation drawer"), "Picker locations", tap=True)
        wait(lambda n: label(n, "Downloads"), "Downloads", tap=True)
        field = wait(lambda n: n.get("class") == "android.widget.EditText", "Export filename")
    input_text(field, basename)
    wait(lambda n: label(n, "SAVE"), "Save", tap=True)
    wait(lambda n: "Web game saved." in n.get("text", ""), "Web game saved")
    capture("android-release-saved.png")
    archive = output / basename
    device("pull", "/sdcard/Download/" + basename, str(archive))
    folder = output / ("export-" + token)
    folder.mkdir()
    with zipfile.ZipFile(archive) as pack:
        if pack.testzip() is not None:
            raise ValueError("Damaged exported archive")
        for member in pack.namelist():
            if not (folder / member).resolve().is_relative_to(folder.resolve()):
                raise ValueError("Invalid exported asset path")
        pack.extractall(folder)
    game = json.loads((folder / "game.spawnkit.json").read_text(encoding="utf-8"))
    if game["title"] != "Coin chase":
        raise AssertionError(f"Expected the release title, found {game['title']!r}")
    class WebHost(SimpleHTTPRequestHandler):
        extensions_map = {**SimpleHTTPRequestHandler.extensions_map, ".mjs": "text/javascript", ".wasm": "application/wasm"}

        def log_message(self, fmt, *args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), partial(WebHost, directory=folder)) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            subprocess.run([sys.executable, str(Path(__file__).with_name("release-player-smoke.py")),
                            f"http://127.0.0.1:{server.server_port}/", str(output / "android-export-playing.png"), "--move-right"], check=True)
        finally:
            server.shutdown()
    print(f"PASS: {emulator} exported {archive.name} ({archive.stat().st_size:,} bytes) through Android's save picker.", flush=True)
    if arcade_port:
        publish()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--arcade-port", type=int, help="Also publish the workspace to a local test Arcade on this port.")
    parser.add_argument("--publish-only", action="store_true", help="Only run the Arcade portion against --arcade-port.")
    args = parser.parse_args()
    if args.publish_only and not args.arcade_port:
        parser.error("--publish-only needs --arcade-port")
    run(args.output, args.serial, args.arcade_port, args.publish_only)
