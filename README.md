# BatteryMonitor

An offline Android battery monitor for understanding charging, battery drain, screen usage, and battery health, with an AMOLED black interface and customizable home screen widgets.

**Current version: 1.10.0** · **Android 10 or newer**

## Download and install

Get the APK from the [latest release](https://github.com/gatofile/BatteryMonitor/releases/latest).

1. Download `BatteryMonitor-v1.10.0.apk` on your Android phone.
2. Open the downloaded file and follow Android's installation instructions. If asked, allow your browser or Files app to install this APK.
3. Open BatteryMonitor. No account, root access, or internet connection is needed to use it.

Install updates over the existing app to preserve your settings and recorded sessions. Uninstalling the app removes its local data.

## Features

- **Live dashboard:** battery percentage, temperature, voltage, current, power, charging status, and battery details supported by your phone.
- **Background monitoring:** record readings while the app is closed or the screen is off, with configurable charging and discharging intervals.
- **Charging sessions:** percentage gained, charging speed, current, average and peak power, temperature range, and estimated time to 80% or full.
- **Discharging sessions:** battery consumed, drain per hour, screen-on and screen-off time and drain, and estimated time remaining.
- **Session history:** revisit previous charging and discharging sessions, their statistics, and recorded app usage.
- **Graphs:** inspect battery level, temperature, power, current, and voltage over time. Missing readings appear as gaps.
- **App usage:** see apps used during a session, estimated battery use, and usage details. Android Usage Access is optional and enabled through system settings.
- **Home screen widgets:** editable presets, individual metrics, graphs, colors, typography, and layouts. Each widget has its own settings, including an option to hide its settings button. Resizing depends on your launcher.
- **Data controls:** change monitoring intervals and history retention, or clear recorded logs with confirmation.
- **App updates:** checks GitHub at launch and offers new versions. The first card in Settings shows update status and lets you check manually. Downloads require approval; Android confirms installation.
- **Optional donations:** the Donate button in Settings opens [Ko-fi](https://ko-fi.com/gatofile) in your browser.

## Background monitoring

Enable background monitoring in Settings and allow the notification permission when requested. Android uses a foreground service with a monitoring notification to keep collection active.

On Xiaomi and POCO phones, allow background autostart and unrestricted battery use in the phone's app settings. Android and HyperOS can still interrupt monitoring; force-stopping the app prevents collection until it is opened again. Sleeping devices may leave gaps in the history.

## Measurements and estimates

Available readings depend on the phone. Unsupported measurements display as unavailable, and estimates appear once enough valid data has been collected.

Battery drain, charging times, and capacity estimates use observed readings. App battery percentages are estimates based on phone drain during app use; they are not precise measurements of each app's power consumption.

## Privacy and offline use

Readings, sessions, widget settings, and preferences are stored locally on your device. BatteryMonitor has no ads, accounts, or analytics. Internet access is used to check GitHub releases and download updates you approve; battery readings are not uploaded. Monitoring works offline. Opening the donation link uses your browser.
