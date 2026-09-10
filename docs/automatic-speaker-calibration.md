# Protocol timing fix and automatic calibration investigation

## Initial fix

Both realtime NTP paths now request 88,200 frames (2 seconds at 44.1 kHz)
in their sync packets. Previously AP1 requested 11,025 frames while AP2
requested 88,200. Sharing an NTP anchor with different requested latencies
could therefore introduce 1.75 seconds of skew.

The SDP/plist minimum remains 11,025 frames; minimum buffering and requested
render latency are separate concepts. The existing 250 ms future anchor is
additional startup lead, not the complete playback delay. Standalone AP1 also
gets the larger render allowance, so adding an AP2 receiver no longer requires
changing its requested latency. AP2's packet format and latency are unchanged.

Both senders now use `AirPlaySyncPacket`. Regression tests decode initial and
periodic packets, including actual AP1 packets over loopback, independent RTP
origins after preroll, and timestamp wraparound. Old manual corrections remain
stored under their old preference keys but are not applied to the new model.

This fixes a sender inconsistency, not a measured guarantee of acoustic sync.
[Shairport Sync's receiver](https://github.com/mikebrady/shairport-sync/blob/master/rtp.c)
derives latency from the two RTP fields and can also apply receiver-specific
offsets and limits. Actual Sonos/Symfonisk and Bravia behavior must be measured.
In particular, returned latency values should be logged and interpreted before
adding any extra compensation; blindly adding them may double-count buffering.

## Implemented user flow (hardware validation pending)

1. Select the speaker group and choose automatic calibration.
2. Ask the user to hold the phone close to the named speaker, with its microphone
   unobstructed and approximately the same small distance for every speaker.
3. The user taps **I'm near this speaker**. This only starts measurement; the
   timing of the tap is never used to estimate delay.
4. Send a few quiet, distinctive test bursts to that speaker. Detect them with
   the phone microphone, assess confidence, and retry if the recording is noisy
   or clipped. Keep every other session running with timestamped silence.
5. Repeat for each speaker, then choose the measured latest speaker as the
   reference and calculate non-negative delays for the others.
6. Verify with a second measurement pass before saving. Preserve the previous
   profile on cancellation or an unreliable result.

Being near each speaker reduces travel-time differences and reflections; it
does not eliminate distance mathematically. This procedure aligns speaker
output times, rather than compensating for a particular listening position.

## Measurement implementation

Use a seeded noise burst or chirp with a unique envelope and cross-correlation,
rather than a pure repeating tone with ambiguous peaks. Record the absolute
group PCM frame index of every burst and its scheduled playback time, including
the protocol render latency. Detection must use recorded sample positions,
not the time a read callback arrives or a UDP send completes.

Use one microphone recording session for the entire walk between speakers.
Map its frame positions into the same monotonic clock as the group using
[`AudioRecord.getTimestamp`](https://developer.android.com/reference/android/media/AudioRecord#getTimestamp(android.media.AudioTimestamp,%20int)).
Android exposes the earliest available capture-pipeline timestamp, not a
guarantee of zero microphone-path delay. Restarting recording resets its frame
counter. Use a consistent clock timebase, check timestamp availability, and
reject route changes or recording discontinuities.

For each speaker calculate the median of:

```text
measured residual = detected microphone time - scheduled playback time
correction[i] = max(measured residuals) - measured residual[i]
```

A stable common microphone delay cancels in these relative corrections. That
is an assumption to validate on phones, especially across long calibration
walks. Repeat the first speaker at the end to detect changing clock or microphone
bias. Reject inconsistent measurements rather than persisting false precision.

Prefer the built-in microphone and
[`UNPROCESSED`](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#UNPROCESSED)
when available. Android may fall back to its default processing, so detection
must tolerate processing and verify consistency. Account for differing capture
sample rates when correlating the 44.1 kHz outgoing signal.

## Android integration

The existing `RECORD_AUDIO` permission covers microphone recording as well as
[playback capture](https://developer.android.com/media/platform/av-capture).
The current `AudioCapture` uses `AudioPlaybackCaptureConfiguration`, so it does
not provide microphone samples. Calibration needs a separate microphone
`AudioRecord`; the existing grant must still be checked for revocation.

The implementation uses a dedicated calibration mode with the screen visible, temporarily
replacing captured app PCM with generated test PCM through the production
session/fan-out path. Keep receiver connections open through measurement and
verification. Do not play the probe locally and recapture it, which would add
an unnecessary local playback/capture path. Do not forward microphone audio to
the speakers. Process recordings locally and discard them after analysis.

If microphone capture lives in the foreground service, Android requires the
[`microphone` service type and associated foreground-service permission](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
in addition to `RECORD_AUDIO`, with the applicable while-in-use restrictions.
The current service declares only `mediaProjection`. An activity-owned recorder
that stops when the calibration screen leaves the foreground is another option.

## Remaining hardware validation

The service now numbers group PCM frames, creates the group anchor at the first
complete captured packet, and shares a `NetworkClock` with both protocol timing
responders. Wall-clock changes no longer move NTP replies independently of the
stream. RTP/NTP mappings retain nanosecond precision; slider changes shift the
existing mapping by the delay difference. Joins map only the new receiver onto
the next group frame. All capture chunks are assembled into 352-frame packets
before fan-out, so the two protocols advance at the same PCM boundaries.

Calibration uses an activity-owned microphone recorder and stops on leaving the
screen. The service substitutes generated PCM into its existing fan-out while
capture and receiver connections continue. Three 80 ms chirps are separated by
1.5 seconds; normalized correlation checks for weak signals, competing echoes,
clipping, and inconsistent repetitions. A second pass verifies every receiver
with temporary corrections before Save becomes available. Cancel restores the
original in-memory corrections without changing the stored profile. A heartbeat
expires an abandoned calibration after 30 seconds of continued capture.

The prototype uses the built-in microphone at 44.1 kHz and fails explicitly if
that configuration or timestamps are unavailable. It does not yet resample other
microphone sample rates. The measurement search covers +/-600 ms around the
scheduled playback time; corrections beyond the existing 500 ms range are
rejected rather than silently clamped. These limits and confidence thresholds
need evaluation on real phones and speakers.

First measure mixed AP1/AP2 playback with zero corrections after this fix,
including repeated cold starts and a sustained run. Compare it with AP1-only
and AP2-only groups. If a residual offset remains stable, prototype microphone
measurement and compare it against a simultaneous external recording. Test
multiple phones, noisy rooms, microphone route changes, cancellation, and
receiver reconnection. Changing offsets over time require clock/drift work;
a saved fixed calibration cannot correct drift.
