"""Pure ADB inventory selection. No subprocess, installation, or device mutation."""
import re


def select_emulator(listing: str, requested_serial: str | None = None) -> str:
    records = []
    for line in listing.splitlines():
        match = re.fullmatch(r"\s*(\S+)\s+(\S+)(?:\s+.*)?", line)
        if match and match.group(1) not in ("List", "*"):
            records.append((match.group(1), match.group(2)))
    if requested_serial is not None and requested_serial != "":
        if not re.fullmatch(r"emulator-[0-9]+", requested_serial):
            raise ValueError("Selected target must be emulator-N; physical or invalid serial rejected")
        matches = [state for serial, state in records if serial == requested_serial]
        if matches != ["device"]:
            raise ValueError("Selected emulator must appear exactly once and be device; missing/offline/duplicate rejected")
        return requested_serial
    online = [serial for serial, state in records if re.fullmatch(r"emulator-[0-9]+", serial) and state == "device"]
    if len(online) != 1 or sum(serial == online[0] for serial, _ in records) != 1:
        raise ValueError("Without a selected serial, exactly one online emulator is required")
    return online[0]
