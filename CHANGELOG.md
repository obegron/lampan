# Changelog

## v0.5.2

- Exclude Android Gradle Plugin dependency metadata from APK signing blocks for
  F-Droid scanner compatibility.
- Publish both the Standard and opt-in Now Playing APKs in GitHub releases.

## v0.5.1

- Correct the Android version name and code after the `v0.5.0` tag retained
  the previous internal `0.4.9` version.
- Make source-revision metadata deterministic so independent builds of the same
  tag can be compared for reproducibility.
- Add upstream F-Droid/Fastlane listing metadata.

## v0.5.0

- Scope remembered receiver lists to the current Wi-Fi network while safely
  migrating reachable receivers saved by older versions.
- Add manual per-group receiver delay controls with a selectable timing
  reference for improving multi-device synchronization.
- Add long-press receiver management with configure and remove actions.
- Allow a selected receiver to be detached or added while streaming.
- Add a new adaptive lamp-and-audio application icon.

## v0.4.2

- Allow experimental groups containing AirPlay 1, AirPlay 2, or a mix of both.
- Map each receiver's independent RTP counter to one future NTP start anchor.
- Preserve that RTP-to-NTP mapping in periodic sync packets instead of losing
  the scheduled group offset after the first sync interval.

## v0.4.1

- Sign GitHub release APKs with one backed-up, long-lived Lampan release key
  instead of the temporary debug key created by each GitHub Actions runner.
- Verify the expected signing-certificate fingerprint before publishing the APK.
- Require one final reinstall for v0.4.1; later releases will install as normal
  updates because they retain the same signing identity.

## v0.4.0

- Add native AirPlay 2 audio streaming alongside the existing AirPlay 1 path.
- Discover both `_raop._tcp` and `_airplay._tcp` receivers, merge duplicate device records, and choose the appropriate streaming path automatically.
- Use one Stream action after device selection; keep the protocol choice only as a fallback for manually entered addresses.
- Prefer the broadly compatible AirPlay 1 route when a receiver advertises both
  transports, while allowing a remembered per-device AirPlay 2 choice for
  receivers such as the Sony Bravia. This keeps Sonos/Symfonisk on AirPlay 1
  because their native AirPlay 2 route requires privileged PTP ports.
- Allow selecting multiple known AirPlay 2 receivers, prepare every session before capture, and fan out identical PCM using one shared RTP/NTP start mapping.
- Remember multiple receiver capability records, restore the last selected transport, and list known receivers separately from the add-device scan/manual flow.
- Probe `GET /info` while idle to show receiver availability and verify the stable receiver identity before reusing a remembered address.
- Treat AirPlay `deviceID` and pairing identity (`pi`) as aliases for the same receiver, preventing false address-change warnings after discovery.
- Probe and display reachability independently for every selected receiver instead of showing status only for the primary group member.
- Complete HomeKit pair-setup and pair-verify, then establish encrypted RTSP control and reverse event channels.
- Add NTP session timing, RECORD/stream SETUP, fixed-frame ALAC encoding, encrypted realtime RTP, sync, feedback, and retransmission handling.
- Encrypt persisted AirPlay 2 receiver credentials and successfully authenticated receiver passwords with Android Keystore, and exclude them from backups.
- Show pairing, verification, encrypted-control, and failure checkpoints in Lampan's on-phone session log.
- Keep routine AirPlay 2 feedback exchanges quiet and report one compact stream-health message per minute.
- Send volume changes when the slider is released and suppress their routine wire dump.
- Announce initial DMAP track metadata before sending PCM for improved AirPlay 2 receiver compatibility.
- Try the standard `3939` transient setup value automatically when an AirPlay 2 receiver has no saved or entered password, remembering it only after successful authentication.
- Format AirPlay volume parameters with protocol-required decimal points regardless of the phone locale.
- Accept custom AirPlay passwords and normalize eight-digit displayed HomeKit codes.
- Repair AirPlay 1 startup on Sonos/Symfonisk by restoring the proven RECORD,
  volume, progress, sync, and silent-preroll sequence, with explicit timing-port
  health logging.

## v0.3.2

- Fix audio capture startup on current Android targets by registering the required `MediaProjection.Callback` before starting playback capture.
- Keep Android's required playback-capture audio permission flow, but avoid asking again once permission is already granted and clarify the denial message.
- Release `AudioRecord` more defensively when capture stops or startup fails.
