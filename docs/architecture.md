# System Architecture: Zero-Cloud Smartwatch Companion Stack

**Author:** Ayush Soni  
**Target Hardware:** Amazfit Pace (A1612) + Android Phone  
**Operating Systems:** Android 5.1 Lollipop (Watch, MIPS32) & Modern Android 14/15 (Phone)  

---

## 1. High-Level Architecture Overview

The system is designed with a strict zero-trust, zero-intermediary cloud model. The only external network call permitted in the entire pipeline is the direct OAuth-authenticated push from the phone companion app to the user's personal Google Fit / Health Connect API.

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
│   │NotificationListener│   │ Watch Face Library │   │  Google Fit  │   │
│   │(Capture & Dismiss) │   │ (Local File Push)  │   │ Sync Engine  │   │
│   └────────────────────┘   └────────────────────┘   └──────┬───────┘   │
│                          ANDROID SMARTPHONE                │           │
└────────────────────────────────────────────────────────────┼───────────┘
                                                             │
                                                             ▼
                                                    Google Fit / Health
                                                    Connect (Personal)
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
* `0x20` — `WATCHFACE_TRANSFER`: Chunked binary upload of a `.wfz` file into `/sdcard/WatchFace/`.

---

## 3. Google Fit & Health Connect Integration Engine

The phone companion utilizes Android's **Health Connect** or **Google Fit History API**:

```kotlin
// Example payload mapping from watch sensor frame to Google Fit DataPoint
fun insertHeartRateBatch(records: List<HeartRateRecord>) {
    val dataSource = DataSource.Builder()
        .setAppPackageName(context)
        .setDataType(DataType.TYPE_HEART_RATE_BPM)
        .setType(DataSource.TYPE_RAW)
        .build()

    val dataSet = DataSet.builder(dataSource)
    records.forEach { record ->
        dataSet.add(
            DataPoint.builder(dataSource)
                .setTimeInterval(record.timestamp, record.timestamp, TimeUnit.MILLISECONDS)
                .setField(Field.FIELD_BPM, record.bpm)
                .build()
        )
    }

    Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context))
        .insertData(dataSet.build())
        .addOnSuccessListener { Log.i("Sync", "Successfully synced to Google Fit") }
}
```

---

## 4. Debloating & Hardening the Watch Environment

To guarantee that no legacy Huami daemons run in the background, we systematically disable or strip Huami system packages:
* `com.huami.watch.wearservices` — Disable cloud upload tasks.
* `com.huami.watch.otawatch` — Prevent OTA overwrites.
* `com.huami.watch.wifiuploaddata` — Strip automated Wi-Fi telemetry sync.
* `com.huami.wififtp` — Disable open background FTP server.
