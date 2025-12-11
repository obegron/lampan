# Lampan - AirPlay Audio Streamer for Android

Lampan is a lightweight Android application that allows you to stream your device's system audio directly to AirPlay 1 compatible speakers, specifically designed with the **IKEA Symfonisk** (Sonos) range in mind.

## Motivation

I bought an IKEA Symfonisk speaker and was disappointed to discover it was largely locked to the Sonos ecosystem (Sonos Radio, Spotify Connect, etc.). I couldn't simply use it as a general-purpose speaker for YouTube, browser audio, or other apps on my Android phone.

Lampan solves this by capturing internal audio and streaming it via the AirPlay protocol, giving you the freedom to use your speaker with *any* app.

## Features

*   **System-Wide Audio Capture:** Streams audio from any app on your phone (requires Android 10+).
*   **AirPlay 1 Support:** Compatible with older AirPlay devices and Sonos/Symfonisk speakers.
*   **Device Discovery:** Automatically scans your network for available AirPlay receivers.
*   **Volume Control:** Adjust the speaker volume directly from the app.
*   **Background Service:** Keeps streaming even when you switch apps or lock the screen.

## Installation

Install the APK on your Android device.

## Usage

1.  Ensure your Android phone and your speaker are on the **same Wi-Fi network**.
2.  Open **Lampan**.
3.  Tap **Scan for AirPlay Devices**.
4.  Select your speaker from the dropdown list.
5.  Tap **Connect & Stream**.
6.  Grant the necessary permissions (Microphone/Audio Capture).
7.  The app will start capturing audio. Play music or video in any other app, and it will hear it on your speaker.

## Known Limitations

*   **Latency:** due to the nature of AirPlay 1 buffering, there is a delay (typically 2 seconds). This makes it perfect for music, podcasts, and audiobooks, but it is **not suitable for real-time gaming** or lip-synced video watching.
*   **DRM:** Some apps (like Netflix or banking apps) block screen/audio capture for security reasons. Lampan cannot stream audio from these apps.

## Requirements

*   Android 10 (API level 29) or higher.
*   An AirPlay-compatible speaker (tested on IKEA Symfonisk).
