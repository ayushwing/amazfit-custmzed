---
title: "Reviving a 10-Year-Old Smartwatch: Hardware Archeology and The Real Cost of 'Free' Wearables (Part 1)"
date: "2026-11-23"
author: "Ayush Soni"
tags: ["hardware", "android", "privacy", "reverse-engineering", "distributed-systems", "field-notes"]
description: "A deep dive into resurrecting an old Amazfit Pace, uncovering hidden Chinese analytics SDKs in consumer companion apps, and architecting an open-source, zero-cloud alternative."
---

# Field Notes: Reviving a 10-Year-Old Smartwatch & Reverse-Engineering Wearable Telemetry

Every few years, we accumulate hardware that gets retired not because the silicon failed, but because software moved on. Recently, while rummaging through an old storage box, I rediscovered my old **Amazfit Pace (Model A1612)** smartwatch. 

Released nearly a decade ago by Huami (the hardware manufacturer behind Xiaomi's Mi Band), it was an intriguing piece of hardware in its heyday: a transflective always-on memory LCD display, built-in optical heart rate monitoring, discrete GPS/GLONASS, and a 5-day battery life.

When I plugged it into my workstation, the screen stayed black. It seemed dead. But after a low-level USB bus audit, kernel tracing, and firmware inspection, not only did it spring back to life, but peeling back the layers of its software ecosystem turned into a fascinating case study in hardware archeology, telemetry auditing, and local-first software architecture.

Here is the breakdown of what happened, what we uncovered inside the official companion stack, and why we decided to build our own zero-cloud companion from scratch.

---

## 1. The Resurrection: Hardware Diagnostics Over USB

When an old lithium-ion battery sits discharged for years, its terminal voltage drops below the safety threshold (typically below ~2.5V). When you plug it into a computer USB port, you hit a classic brownout loop: the port negotiates 500mA, the charging circuit tries to initialize the display and main processor, the voltage sags, the controller crashes, and the port resets.

In the macOS kernel logs, this manifests as a relentless enumeration failure:

```text
kernel: [Port-USB-C@2: USB2] Setting nominal signaling frequencies...
kernel: (IOUSBHostFamily) AppleUSBHostPort::disconnect: persistent enumeration failures
```

After cleaning the oxidized copper pogo pins on the charging cradle with isopropyl alcohol, giving the battery a sustained low-voltage trickle charge, and inspecting the bus via `libusb`, the hardware negotiated cleanly:

```text
Bus 001 Device 008: ID 226f:0e09 Ingenic Semiconductor Inc.
Model: Amazfit_Pace (huangheUS)
OS: Android 5.1 (Lollipop), Kernel 3.10.14-gb7c467e
Architecture: Ingenic XBurst M200 (MIPS32r2)
```

The watch wasn't a brick—it was a full-fledged, dual-core **MIPS32 Linux computer sitting on my desk with ADB access enabled**.

---

## 2. The Trap: The True Cost of "Free" Smartwatch Ecosystems

Naturally, the next logical step was connecting the watch to a modern smartphone. The default path is downloading the official companion app (**Zepp**, formerly Amazfit / Mi Fit).

Modern companion apps ask for an exhaustive suite of permissions: `ACCESS_BACKGROUND_LOCATION`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `READ_CONTACTS`, `BODY_SENSORS`, `WRITE_EXTERNAL_STORAGE`. 

As backend engineers, we know data is rarely collected just to render a UI graph. I decided to pull the companion binaries and audit the telemetry pipelines.

### The Forensic Discovery

Extracting and scanning the bytecode (`classes.dex`) and network calls of the companion frameworks revealed hardcoded endpoints pointing directly to third-party ad networks and Chinese analytics aggregators:

```text
http://alog.umeng.co/app_logs
http://alog.umeng.com/app_logs
https://data.mistat.xiaomi.com/mistats
https://data.mistat.xiaomi.com/micrash
https://data.mistat.xiaomi.com/realtime_network
```

* **Umeng Analytics (Alibaba Group):** One of China's largest tracking networks, correlating behavioral device fingerprints across multi-app installations.
* **Xiaomi MiStats:** Capturing real-time execution graphs, hardware identifiers (IMEI, Bluetooth MAC, Wi-Fi BSSID), and crash traces.
* **Biometric & Spatial Pipeline:** Continuous heart rate samples, sleep architecture data, and high-resolution GPS tracks uploaded to remote cloud databases.

Even popular open-source community companion projects were caught in this web: because they imported Huami's pre-compiled `.jar` Bluetooth protocol libraries to avoid reverse-engineering the radio protocol from scratch, they inadvertently bundled the compiled `com.umeng.analytics` and `com.xiaomi.mistatistic.sdk` binaries into their APKs.

---

## 3. The Pivot: Building Our Own Zero-Cloud Stack

If you want:
1. **Biometric data syncing into Health Connect on the phone** (no servers at all).
2. **App notification forwarding and wrist-based dismissal** (without an app reading your chat logs for analytics).
3. **Custom watch faces and offline music storage** (managed cleanly over local Bluetooth and USB).

The only architectural answer is a **clean-room implementation**:

```
[ Watch (Android 5.1 / MIPS) ] 
              │
       Bluetooth RFCOMM 
    (Local Peer-to-Peer)
              │
[ Phone Companion (Kotlin) ] ──(on-device)──► [ Health Connect ]
```

---

## 4. What's Coming in Part 2

In **Part 2**, we will roll up our sleeves on the watch side:
* Stripping out all legacy Huami telemetry daemons from `/system/app/` using ADB.
* Writing a lightweight Java service targeting Android 5.1 / MIPS.
* Establishing a deterministic, framing-aware RFCOMM Bluetooth server on the watch to stream step counters and heart rate data directly.

Stay tuned. The full code, debloat scripts, and protocol specs are being open-sourced alongside this series.
