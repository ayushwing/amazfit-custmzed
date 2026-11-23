# System Architecture: Zero-Cloud Smartwatch Companion Stack

**Author:** Ayush Soni  
**Target Hardware:** Amazfit Pace (A1612) + Android Phone  
**Operating Systems:** Android 5.1 Lollipop (Watch, MIPS32) & Android 14+ (Phone, Samsung Galaxy S23)  

> Status: this is the target design. The watch launcher exists; the RFCOMM link, phone companion and Health Connect sync are not built yet.

---

## 1. High-Level Architecture Overview

The system is designed with a strict zero-intermediary model: no network call anywhere in the pipeline. Health Connect is an on-device store on the phone, so health data goes watch → Bluetooth → phone → Health Connect and never leaves the phone. (The Google Fit APIs are being retired in 2026 and are not used.)

```
┌────────────────────────────────────────────────────────────────────────┐
│                          AMAZFIT PACE (WATCH)                          │
│                                                                        │
│   ┌────────────────────┐   ┌────────────────────┐   ┌──────────────┐   │
│   │ Android 5.1 Sensors│   │ Notification UI    │   │ /sdcard/     │   │
│   │ (Step/Heart Rate)  │   │ (Cards + Actions)  │   │ WatchFace/   │   │
│   └─────────┬──────────┘   └─────────▲──────────┘   └──────▲───────┘   │
│             │                        │                     │           │
│   ┌─────────▼────────────────────────┴─────────────────────┴───────┐   │
│   │           WatchDaemon (Background Service / RFCOMM Server)      │   │
│   └──────────────────────────────────▲─────────────────────────────┘   │
└──────────────────────────────────────┼─────────────────────────────────┘
                                       │ Standard Bluetooth Socket (RFCOMM)
                                       │ Encrypted Point-to-Point Local Link
┌──────────────────────────────────────┼─────────────────────────────────┐
│   ┌──────────────────────────────────▼─────────────────────────────┐   │
│   │          Companion App Service (RFCOMM Client / Router)        │   │
│   └─────────┬────────────────────────┬─────────────────────┬───────┘   │
│             │                        │                     │           │
│   ┌─────────▼──────────┐   ┌─────────▼──────────┐   ┌──────▼───────┐   │
│   │NotificationListener│   │ Watch Face Library │   │Health Connect│   │
│   │(Capture & Dismiss) │   │ (Local File Push)  │   │ Sync Engine  │   │
│   └────────────────────┘   └────────────────────┘   └──────┬───────┘   │
│                                                            ▼           │
│                                                   Health Connect store │
│                          ANDROID SMARTPHONE        (on-device only)    │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Communications Protocol (Binary Framing over RFCOMM)

Rather than maintaining fragile and complex BLE GATT profile states for high-throughput sensor sync, the pipeline utilizes standard Android **Bluetooth RFCOMM (Serial Port Profile)** with a deterministic packet frame:

### Packet Header Specification
```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Magic (0x50 0x41)       |   Msg Type    |    Flags      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Payload Length                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        Payload Data...                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          CRC32 Check                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Supported Message Types (`Msg Type`)
* `0x01` — `NOTIF_PUSH`: Forward new notification (JSON payload: ID, App, Title, Body).
* `0x02` — `NOTIF_ACTION`: Dismissal or quick-reply action triggered from the watch.
* `0x10` — `HEALTH_SYNC_REQ`: Phone requests buffered sensor readings.
* `0x11` — `HEALTH_SYNC_DATA`: Watch streams step counts, heart rate timestamps, sleep blocks.
* `0x20` — `WATCHFACE_TRANSFER`: Chunked binary upload of a `.wfz` file. Target path still to be confirmed: `/sdcard/WatchFace/` exists but is empty on this firmware, and face data lives under `/sdcard/.watchface/`.

### Watch-side data sources (verified)
* `Sensor.TYPE_STEP_COUNTER` (type 19) and `Sensor.TYPE_HEART_RATE` (type 21, needs `BODY_SENSORS`), readable by an ordinary app.
* `content://com.huami.watch.health.historydata`: one row per day, JSON `summary` with `ttl` (steps), `dis`, `cal` and hourly `stage` buckets. Readable without permission.
* `content://com.huami.watch.health.heartdata`: only holds manual spot checks on this watch. Continuous heart rate is not there; its source is still open.

---

## 3. Health Connect Integration

The phone companion writes records with the Health Connect client (`androidx.health.connect:connect-client`). On Android 14+ Health Connect is part of the OS; nothing leaves the device.

```kotlin
// Map one watch sync frame to Health Connect records.
suspend fun writeSync(client: HealthConnectClient, sync: WatchSync) {
    val zone = ZoneOffset.systemDefault().rules.getOffset(sync.start)
    val records = buildList<Record> {
        add(StepsRecord(
            startTime = sync.start, startZoneOffset = zone,
            endTime = sync.end, endZoneOffset = zone,
            count = sync.steps.toLong(),
        ))
        if (sync.heartRate.isNotEmpty()) add(HeartRateRecord(
            startTime = sync.start, startZoneOffset = zone,
            endTime = sync.end, endZoneOffset = zone,
            samples = sync.heartRate.map { HeartRateRecord.Sample(it.time, it.bpm.toLong()) },
        ))
    }
    client.insertRecords(records)
}
```

The app requests `WRITE_STEPS` and `WRITE_HEART_RATE`, and uses `clientRecordId` per watch interval so a re-sync updates instead of duplicating.

---

## 4. Hardening the Watch

No root is available (the bootloader is locked on this firmware), and Android 5.1 refuses `pm disable-user` from the ADB shell. The shell does hold `MANAGE_USERS`, so packages are hidden with `pm hide` (reversible with `pm unhide`). `scripts/watch-debloat.sh` hides one at a time and reboots and checks after each:
* `com.huami.watch.wifiuploaddata`: Wi-Fi data upload.
* `com.huami.wififtp`: Wi-Fi FTP server.
* `com.huami.watch.otawatch`: firmware updates.
* `com.huami.watch.wearlogger`: system logger.
* `com.huami.watch.hmlab`: lab features.

Left enabled on purpose: `health`, `sensorservice`, `wearservices` and `launcher`. Step counting and the always-on face run inside them. With Wi-Fi off and no Zepp app on the phone, they have no route off the watch.
