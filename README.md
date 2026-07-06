# TeslaSync

**Pull Tesla dashcam clips to your phone over WiFi — from a tiny Linux board that pretends to be the car's USB drive.**

A Tesla saves dashcam and Sentry footage onto a USB stick you have to physically unplug and carry to a computer. TeslaSync replaces that stick with a low-power single-board computer (Radxa Zero 3W) that **emulates a USB drive** to the car, records exactly like a real stick would, and then serves the footage over its own WiFi — so you browse and download the clip you actually want from your phone, without ever touching the hardware again.

> A personal side project about taking a "vibe coding" workflow all the way into real hardware, Linux USB internals, and a physical Tesla. **Everything on the device side is working and validated in an actual car** — the phone app is the remaining piece.

---

## Status

| Component | Status |
|---|---|
| USB drive emulation — car recognizes it as a real dashcam stick | ✅ **Verified on a real Tesla** |
| Continuous recording · manual Save · Sentry events | ✅ Recording to the board |
| Snapshot-based archiving + free-space management | ✅ Working |
| Retrieval over WiFi (web browser + SMB) | ✅ Working |
| Mobile-first dark web UI (touch targets, WCAG contrast) | ✅ Working |
| Android companion app (BLE proximity trigger + one-tap download) | 🚧 In progress |

---

## How it works

```
Tesla USB port
   │  (USB-A ↔ USB-C cable — data + power)
Radxa Zero 3W  ── plugged into the OTG port
   │
   ├─ USB gadget mode: a large file on the microSD is exposed as a fake
   │    USB drive, so the car writes TeslaCam/{SavedClips,SentryClips,RecentClips}
   │    to it — believing it's an ordinary memory stick
   │
   ├─ teslausb: sets up the gadget + safely archives finished clips
   │    (snapshots the disk so it never reads a file the car is mid-write on)
   │
   └─ nginx web UI: browse & download clips from any phone on the board's WiFi

Phone / laptop  ── same WiFi ── browse the event list, pull the one clip you want
```

**Why WiFi and not Bluetooth?** BLE tops out around 1–2 Mbps. A single 1080p minute is tens of MB (this car pushes ~266 MB/min across 6 cameras). So Bluetooth is used only as a *proximity trigger* ("I got in the car"); the actual video moves over WiFi.

---

## Why it wasn't trivial (the interesting part)

This is where the project stopped being a config-file exercise and turned into real debugging:

- **Ran the stack on an unsupported board.** The base project ([`marcone/teslausb`](https://github.com/marcone/teslausb)) targets the Raspberry Pi. With the Pi Zero 2 W globally out of stock, I switched to a **Radxa Zero 3W (Rockchip RK3566)** — a board with *no recorded successful teslausb setup.* No copy-paste guide existed.
- **Brought up USB gadget mode from first principles.** Confirmed the SoC's USB controller (dwc3, not dwc2) and the exact device-tree node by cross-referencing the **board schematics against the Linux kernel source**, then enabled peripheral mode via an Armbian device-tree overlay — before ever touching the hardware.
- **Fixed a crash that only happened after reboot.** Samba kept dying on boot because teslausb makes the root filesystem read-only (for SD-card longevity). Root-caused it to `secrets.tdb` write failures and relocated Samba's state onto the writable partition with bind mounts.
- **Diagnosed a boot-time USB race from logs.** The car occasionally showed "no storage device" at power-on. Traced it in `archiveloop` logs to the gadget flapping (connect → disconnect for fsck/archive → reconnect) during boot — and the real-car test confirmed it's cosmetic: recording continued uninterrupted (132 segments across a drive) once the drive re-stabilized.
- **Validated on a real Tesla.** Continuous recording, manual save, and Sentry all confirmed writing to the emulated drive, then retrieved over WiFi.

Full build story, decisions, and debugging write-ups (in Korean) are in [`DEVLOG.md`](DEVLOG.md).

---

## Tech & domains touched

`Linux (Armbian)` · `systemd` · **`USB gadget mode (configfs / dwc3)`** · `device-tree overlays` · `Samba/CIFS on a read-only root` · `nginx + fancyindex` · `Bash` · **`Android / Kotlin`** · `CompanionDeviceManager (BLE proximity)` · `WifiNetworkSpecifier` · `MediaStore`

---

## Repository

| Path | What |
|---|---|
| [`README.md`](README.md) | You are here |
| [`DEVLOG.md`](DEVLOG.md) | 🇰🇷 Full build log — parts list, dead-ends, bugs, fixes, tips |
| [`PLAN.md`](PLAN.md) | 🇰🇷 Design rationale & decisions |
| [`PROGRESS.md`](PROGRESS.md) | 🇰🇷 Running journal + measured data (dashcam formats, bitrates) |
| [`pi/RADXA_SETUP.md`](pi/RADXA_SETUP.md) | 🇰🇷 Exact reproduction procedure for the board |
| [`pi/`](pi/) | BLE advertising + AP/BLE coexistence test scripts |
| [`android/`](android/) | Android app skeleton (CompanionDeviceManager pairing works; sync/browse logic being rewritten) |

The detailed engineering docs are kept in Korean; this README is the English entry point.

---

## Roadmap

1. Rewrite the Android app's sync logic into a **clip browser** (event list → pick a camera → download one clip) against the board's file-serving endpoint.
2. Surface **RecentClips** (continuous footage) in the web UI, not just saved/Sentry events.
3. Optional hardware polish: always-on 12 V power to remove the cold-boot delay entirely.

---

## Credits & disclaimer

Built on top of [**marcone/teslausb**](https://github.com/marcone/teslausb) (USB emulation + archiving), which is installed separately — this repo does not modify or redistribute it. Android proximity flow started from Google's `android/platform-samples`.

Not affiliated with, endorsed by, or connected to Tesla, Inc. "Tesla" is used only to describe compatibility. Use at your own risk.

## License

[MIT](LICENSE) © 2026 Hyu
