#!/usr/bin/env python3
"""
Install the latest debug APK from the build output directory to one or all
connected adb devices.

APK is read directly from app/build/outputs/apk/debug/ -- no stale
cache involved.

- No serial arg  -> enumerate every connected adb device and install in
  parallel (non-blocking). One device failing does NOT affect the others.
- Serial arg     -> install only to that device (adb -s <serial>).

Exit code is 0 only if every targeted device installed successfully.
"""

import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor


def find_latest_apk(apk_dir):
    """Return the path of the most recently modified .apk in apk_dir."""
    if not os.path.isdir(apk_dir):
        return None
    apks = [f for f in os.listdir(apk_dir) if f.lower().endswith(".apk")]
    if not apks:
        return None
    apks.sort(key=lambda f: os.path.getmtime(os.path.join(apk_dir, f)), reverse=True)
    return os.path.join(apk_dir, apks[0])


def get_devices():
    """Return list of (serial, extra_info) for online adb devices."""
    out = subprocess.run(
        ["adb", "devices", "-l"], capture_output=True, text=True
    ).stdout
    devices = []
    for line in out.splitlines():
        line = line.strip()
        if not line or line.startswith("*") or line.startswith("List"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        if state == "device":  # skip offline/unauthorized
            devices.append((serial, " ".join(parts[2:])))
    return devices


def install_to(serial, apk_path):
    """Install apk to a single device. Returns (serial, ok, output)."""
    cmd = ["adb", "-s", serial, "install", "-r", apk_path]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        ok = proc.returncode == 0
        output = (proc.stdout + proc.stderr).strip()
    except subprocess.TimeoutExpired:
        ok = False
        output = "install timed out after 300s"
    except Exception as e:
        ok = False
        output = f"exception: {e}"
    return serial, ok, output


def main():
    root = os.path.dirname(os.path.abspath(__file__))
    apk_dir = os.path.join(root, "app", "build", "outputs", "apk", "debug")
    apk_path = find_latest_apk(apk_dir)

    if not apk_path:
        print(f"[ERROR] No APK found in {apk_dir}. Run `gradlew assembleDebug` first.")
        return 1

    print(f"Installing APK: {apk_path}")

    serial_arg = sys.argv[1] if len(sys.argv) > 1 else None

    if serial_arg:
        devices = [(serial_arg, "")]
        print(f"Target device (specified): {serial_arg}")
    else:
        devices = get_devices()
        if not devices:
            print("[ERROR] No adb devices connected (or all offline/unauthorized).")
            return 1
        print(f"Found {len(devices)} device(s), installing in parallel:")
        for s, info in devices:
            print(f"  - {s} {info}".rstrip())

    results = []
    with ThreadPoolExecutor(max_workers=len(devices)) as pool:
        futures = [pool.submit(install_to, s, apk_path) for s, _ in devices]
        for fut in futures:
            results.append(fut.result())

    print("\n----- Install results -----")
    all_ok = True
    for serial, ok, output in results:
        status = "OK " if ok else "FAIL"
        if not ok:
            all_ok = False
        print(f"[{status}] {serial}")
        for line in output.splitlines():
            print(f"        {line}")

    if all_ok:
        print("\nAll installs completed successfully.")
        return 0
    print("\nSome installs failed (see above). Others were unaffected.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
