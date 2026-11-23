# Forensic Telemetry Audit: Zepp (Huami / Xiaomi) Ecosystem

**Author:** Ayush Soni  
**Target Device:** Amazfit Pace (A1612 / `huangheUS`)  
**Ecosystem Components:** Zepp Companion App, Huami Watch Services, AmazMod (Legacy)  
**Classification:** Privacy & Network Architecture Analysis  

---

## 1. Executive Summary

Consumer wearable devices capture some of the most intimate physical and behavioral data possible: resting heart rates, sleep architecture, precise GPS traces, real-time location, notification contents, and physical movement patterns. 

When reverse-engineering and auditing the official Zepp (formerly Amazfit / Mi Fit) software stack and community libraries, our binary and network inspection revealed extensive, multi-layered telemetry pipelines routing biometric and environment telemetry to Chinese analytics vendors and cloud infrastructure.

```
┌─────────────────────────────────────────────────────────────┐
│                 Zepp / Huami Android Stack                  │
└──────┬───────────────────────┬───────────────────────┬──────┘
       │                       │                       │
       ▼                       ▼                       ▼
┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│ Xiaomi Cloud │       │    Umeng     │       │   Alibaba    │
│  (MiStats)   │       │  (Alibaba)   │       │ Infrastructure│
└──────────────┘       └──────────────┘       └──────────────┘
```

---

## 2. Hardcoded Network Endpoints Discovered

During deep static analysis of the Huami companion framework (`com.huami.watch.*`) and associated SDK binaries, the following external endpoints were verified:

### A. Xiaomi Analytics & Crash Reporting Pipeline
* `https://data.mistat.xiaomi.com/mistats` — Core behavioral telemetry collector (app usage, screen time, button clicks).
* `https://data.mistat.xiaomi.com/micrash` — Stack traces, memory dumps, and device state reports.
* `https://data.mistat.xiaomi.com/realtime_network` — Active network telemetry and connection quality logging.

### B. Umeng Analytics (Alibaba Group)
* `http://alog.umeng.co/app_logs`
* `http://alog.umeng.com/app_logs`
* `http://log.umsns.com/share/api/`
* *Purpose:* Umeng is China's largest third-party mobile analytics and tracking network. It correlates device fingerprints across different apps running on the same handset.

### C. Hardcoded In-App Domains
* `api.waqi.info` — Air Quality Index location scraping.
* `api.openweathermap.org` — Unencrypted coordinate transmission for weather feeds.

---

## 3. Data Telemetry Payload Matrix

| Category | Data Fields Captured | Destination | Risk Level |
| :--- | :--- | :--- | :--- |
| **Biometric** | Continuous PPG Heart Rate (BPM), Sleep stages (REM/Deep/Light), Step frequencies, VO2 Max estimates | Huami Cloud (`*.huami.com`) | **CRITICAL** |
| **Spatial / Geolocation** | High-precision GPS latitude/longitude traces, Elevation profile, Speed | Huami Cloud & Mapping Services | **CRITICAL** |
| **Device & Network Identity** | Phone IMEI, Watch Bluetooth MAC (`D8:80:3C:...`), Wi-Fi SSID/BSSID, Android ID | Xiaomi MiStats & Umeng | **HIGH** |
| **Interpersonal / Behavioral** | Notification package names, interaction timestamps, alarm routines | Local Bluetooth & Cloud Sync | **HIGH** |

---

## 4. Why Open-Source Clones (e.g. Early AmazMod) Failed the Privacy Test

Even well-intentioned open-source tools like the original AmazMod companion app imported Huami's compiled `.jar` library (`com.huami.watch.companion`) to handle the complex Bluetooth protocol. 

Because that binary blob was imported directly, it silently embedded the `com.xiaomi.mistatistic.sdk` and `com.umeng.analytics` bytecode directly into the APK. 

---

## 5. Architectural Countermeasure: Clean-Room Zero-Cloud Stack

To achieve true privacy, we cannot rely on wrapping Huami’s companion libraries. The only mathematically private solution is:
1. **Wipe / Freeze** all Huami cloud services on the watch via ADB.
2. **Implement our own standard RFCOMM Bluetooth socket daemon** on the watch.
3. **Build a pure Kotlin/Java companion** on Android using Android's native `NotificationListenerService` and `Health Connect / Google Fit API`.
4. **Zero external dependencies**; data travels strictly `Watch -> Local Bluetooth -> Phone -> Personal Google Fit`.
