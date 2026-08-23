# Lampan - AirPlay Audio Streamer for Android

Lampan is a lightweight Android application that streams your device's system audio directly to AirPlay receivers. It supports AirPlay 1 speakers such as **IKEA Symfonisk** (Sonos), plus native encrypted AirPlay 2 audio for compatible receivers.

## Motivation

I bought an IKEA Symfonisk speaker and was disappointed to discover it was largely locked to the Sonos ecosystem (Sonos Radio, Spotify Connect, etc.). I couldn't simply use it as a general-purpose speaker for YouTube, browser audio, or other apps on my Android phone.

Lampan solves this by capturing internal audio and streaming it via the AirPlay protocol, giving you the freedom to use your speaker with *any* app.

## Features

*   **System-Wide Audio Capture:** Streams audio from any app on your phone (requires Android 10+).
*   **AirPlay 1 Support:** Compatible with older AirPlay devices and Sonos/Symfonisk speakers.
*   **Native AirPlay 2 Audio:** Pair with password-protected receivers, automatically try standard passwordless transient setup, securely remember successfully authenticated passwords or pairing credentials, and stream encrypted realtime ALAC audio.
*   **Device Discovery:** Scans both AirPlay 1 and AirPlay 2 services, merges records belonging to the same receiver, and uses AirPlay 1 by default when both are available. The per-device choice can be changed to AirPlay 2 and is remembered.
*   **Known Receivers:** Remembers multiple receiver capabilities and verifies saved addresses with a quiet AirPlay `GET /info` identity check.
*   **Synchronized Receiver Groups (Experimental):** Select multiple known receivers—including a mix of AirPlay 1 and AirPlay 2—to share one capture stream and RTP/NTP start timeline.
*   **Volume Control:** Adjust the speaker volume directly from the app.
*   **Background Service:** Keeps streaming even when you switch apps or lock the screen.

## Installation

Install the APK on your Android device.

## Usage

1.  Ensure your Android phone and your speaker are on the **same Wi-Fi network**.
2.  Open **Lampan**.
3.  Tap **Scan for AirPlay Devices**.
4.  Select your speaker from the dropdown list.
5.  Tap **Stream**.
6.  Grant the necessary permissions (Microphone/Audio Capture).
7.  The app will start capturing audio. Play music or video in any other app, and it will hear it on your speaker.

For a discovered AirPlay 2 receiver, select the device, enter its password when requested, and tap **Stream**. Lampan remembers a password only after the receiver accepts it and protects it with Android Keystore. Later launches restore the receiver from **Known Devices**, verify its identity using `GET /info`, and keep the password controls folded. Choose **Add Device** to scan again or enter an IP and protocol manually.

## Known Limitations

*   **Latency:** due to the nature of AirPlay 1 buffering, there is a delay (typically 2 seconds). This makes it perfect for music, podcasts, and audiobooks, but it is **not suitable for real-time gaming** or lip-synced video watching.
*   **DRM:** Some apps (like Netflix or banking apps) block screen/audio capture for security reasons. Lampan cannot stream audio from these apps.
*   **AirPlay 2 compatibility:** Native audio currently uses the realtime ALAC/NTP path. Receivers requiring PTP cannot use that path from an ordinary Android app because PTP requires privileged UDP ports 319/320; dual-protocol receivers such as Sonos/Symfonisk therefore default to AirPlay 1.
*   **Multiple receivers:** AirPlay 1 and AirPlay 2 sessions can share the sender timeline, but different receiver buffering may still create a fixed output offset. Mixed groups remain experimental until verified audibly on the target receivers.

## Requirements

*   Android 10 (API level 29) or higher.
*   An AirPlay-compatible receiver (AirPlay 1 tested on IKEA Symfonisk; native NTP-timed AirPlay 2 tested on a Sony Bravia).
