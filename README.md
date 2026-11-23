# Project Pace: Zero-Cloud Smartwatch Companion & Health Sync

An open-source, private-by-design companion ecosystem for the **Amazfit Pace (Model A1612)**, engineered to bypass Chinese cloud services and route biometric data directly to **Google Fit / Health Connect**.

Developed by [Ayush Soni](https://ayushwing.com).

---

## 🎯 Project Objectives

1. **Zero-Cloud Health Sync:** Directly sync continuous heart rate, daily steps, and workout GPS tracks from the watch into Google Fit via local Bluetooth RFCOMM. Zero third-party telemetry.
2. **Interactive Notifications:** Forward notifications from your Android phone to your watch with actionable dismiss and quick-reply triggers.
3. **Custom Watch Face Management:** Seamless drag-and-drop management of native `.wfz` and interactive canvas watch faces.
4. **Hardware Archeology Blog Series:** A published engineering breakdown documenting the resurrection, reverse engineering, and custom stack creation on [ayushwing.com](https://ayushwing.com).

---

## 📁 Repository Structure

```text
├── docs/
│   ├── MASTER_PROMPT.md        # Complete prompt for AI agents (Claude, Codex, etc.)
│   ├── architecture.md         # Full system & Bluetooth RFCOMM specification
│   ├── research/
│   │   └── zepp-telemetry-audit.md  # Deep dive into Chinese telemetry endpoints
│   └── blog-series/
│       └── part-1-hardware-revival-and-telemetry.md  # Blog post draft
├── watch-service/              # Lightweight Android 5.1 / MIPS watch daemon
├── companion-app/              # Kotlin Android phone companion
└── scripts/
    └── watch-debloat.sh        # ADB script to wipe Huami tracking services
```

---

## ⌚ Hardware Specifications (Amazfit Pace A1612)

* **Processor:** Ingenic XBurst M200 (Dual-Core MIPS32r2 @ 1.2 GHz)
* **RAM / Storage:** 512 MB LPDDR2 / 4 GB eMMC (~2.1 GB user space)
* **Display:** 1.34-inch Transflective Memory LCD (320x300, 8-color ambient / 64k active)
* **OS:** Android 5.1 Lollipop (API 22), Linux Kernel 3.10.14
* **Radio:** Bluetooth 4.0 Classic / BLE + Wi-Fi 802.11 b/g/n + GPS/GLONASS

---

## 🚀 Quick Start

1. Dock the Amazfit Pace and verify ADB connection:
   ```bash
   adb devices -l
   ```
2. Hardening and stripping legacy background telemetry daemons:
   ```bash
   ./scripts/watch-debloat.sh
   ```
