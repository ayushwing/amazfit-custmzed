# Amazfit Pace, customised: a zero-cloud watch stack

Reviving a 2016 Amazfit Pace (A1612) and replacing everything you see and everything that talks to the network with code that is open and auditable. No Zepp, no Huami cloud, no root.

Written up as field notes on [ayushwing.com](https://ayushwing.com).

## Status

| Piece | State |
|---|---|
| Huami network services hidden on the watch | Done (`pm hide`, reversible) |
| Custom home screen (`watch-launcher/`) | Done: face, bubble app grid, flow-tested on the watch |
| Phone companion (`companion-app/`) | Not started |
| Watch-side sync service (`watch-service/`) | Not started |
| Health Connect sync | Not started |

## What we verified on the hardware

- **No root, and none needed.** The watch runs `huangheUS-2.8.5.0`, after Huami locked the bootloader (firmware 1.2.13 / 1.3.2b, 2017). The community unlock service shut down in 2021, and every custom ROM (PACEfied) is still built on Huami's code. So the plan is to keep Huami's drivers, cut off its network access, and replace the apps.
- **Health data is readable by an ordinary app.** `Sensor.TYPE_STEP_COUNTER` and `Sensor.TYPE_HEART_RATE` are exposed as standard sensors. Huami's `content://com.huami.watch.health.historydata` provider returns daily summaries (steps, distance, calories, hourly stages) without any permission.
- **The panel is physically round.** It is 320x300 pixels at 262 x 246 dpi, so the whole framebuffer is a circle about 1.22" across.
- **Don't lock orientation.** A 320x300 screen counts as landscape. Forcing portrait relaunches Huami's launcher, which crashes when relaunched.
- **The button is "home", not "sleep".** A short press runs `launchHomeFromHotKey`; the screen sleeps on its 14-second timeout.
- **The always-on face is separate.** It is drawn by Huami's low-power SLPT path, which keeps running with a custom launcher.
- **Huami's step counting doesn't use Android's sensor service.** It runs in Huami's own services, so those must stay enabled.

## Layout

```text
watch-launcher/     Home screen for the watch (Java, API 22, no Gradle)
  build.sh            aapt2 -> javac -> d8 -> zipalign -> apksigner
  test-flows.sh       Drives every flow on the real watch over ADB
scripts/
  watch-debloat.sh    Hides Huami network packages one at a time, with checks
docs/
  architecture.md     Target design: watch <-> phone over Bluetooth, Health Connect
  research/           Telemetry audit of the Zepp/AmazMod apps
  blog-series/        Drafts for ayushwing.com
companion-app/      Phone app (not started)
watch-service/      Watch sync service (not started)
```

## Quick start

Requirements: `adb`, OpenJDK 17, and the Android command-line tools with `platforms;android-22` and `build-tools;34.0.0`.

```bash
brew install openjdk@17 android-platform-tools
brew install --cask android-commandlinetools
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-22" "build-tools;34.0.0"
```

Dock the watch, then:

```bash
adb devices -l                          # the watch should be listed
./scripts/watch-debloat.sh              # hide Huami's upload, FTP, OTA, logger, lab
cd watch-launcher && ./build.sh
adb install -r build/pace-launcher.apk
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
                                        # pick "Pace", then "Always" on the watch
./test-flows.sh                         # every flow, with screenshots in build/flows
```

To go back to stock:

```bash
adb uninstall com.ayushwing.pace.launcher
adb shell pm unhide com.huami.watch.otawatch     # repeat per hidden package
```

If ADB vanishes while the watch still charges, re-seat it in the cradle: the pogo pins oxidise and the data pins lose contact first.

## Hardware

Amazfit Pace A1612 (`huangheUS`): Ingenic M200 (MIPS32r2, dual core), 512 MB RAM, 4 GB eMMC, Android 5.1 (API 22), kernel 3.10.14, 320x300 transflective display.
