# Multi-device audio synchronization plan

## Goal

Make one captured audio stream play perceptually in sync on two or more
receivers. This includes AirPlay 1 groups, AirPlay 2 groups, and mixed AirPlay
1/AirPlay 2 groups.

The first target is reliable whole-room listening, not video lip sync or
studio-grade phase alignment. Native AirPlay 2 on receivers that require PTP
ports 319/320, such as the tested Symfonisk, remains a separate problem; that
receiver can still participate through AirPlay 1.

Initial success targets (to be revised after collecting real measurements):

- 20 consecutive group starts without a silent or missing receiver.
- Same-protocol receivers within 10 ms median skew and 20 ms at the 95th
  percentile after startup.
- Mixed AirPlay 1/AirPlay 2 receivers within 25 ms median skew and 40 ms at the
  95th percentile.
- Less than 5 ms additional skew over 10 minutes and less than 15 ms over one
  hour.
- Adding or removing a receiver does not interrupt or re-time receivers already
  playing.

These are engineering targets rather than claims about every receiver. Some
hardware may need a stable per-device correction for its internal DSP and
render buffer.

## Current baseline

Lampan already has the main pieces needed for group synchronization:

- A single `AudioCapture` stream is fanned out to every selected receiver.
- Each receiver keeps its own RTP sequence number and timestamp.
- `RtpNtpTimeline` maps those independent RTP counters to one shared future
  Unix/NTP start time.
- Group startup currently uses a shared start 250 ms in the future.
- AirPlay 1 primes the receiver with silent packets and sends periodic sync
  packets. AirPlay 2 also preserves the shared mapping in periodic sync packets.
- Single-receiver AirPlay 1 and AirPlay 2 playback are working, so changes must
  preserve those paths.

The shared NTP anchor aligns the protocol timelines, but it does not prove that
two receivers produce sound at the same acoustic time. A receiver may add its
own fixed buffer or DSP delay, and its local clock may slowly drift.

```text
captured PCM frame index
          |
          v
   shared group timeline
       /        \
      v          v
 AP1 mapping   AP2 mapping       (receiver-specific offset and clock correction)
      |          |
      v          v
 receiver A    receiver B        (network buffer, decoder, DSP)
      |          |
      +---- acoustic output ----+
```

There are two implementation details to investigate early:

1. Frames are currently sent to sessions sequentially while holding the shared
   receiver lock. A slow encoder or socket can delay every receiver after it.
2. Adding a receiver calls `synchronizeAt()` again on all active sessions. The
   established group should instead keep its timeline while only the joining
   receiver is mapped onto a future frame of that timeline.

## Measure before correcting

### Reproducible test signal

Add an explicit hardware integration test that sends low-volume, seeded white
noise bursts with a distinctive periodic envelope. This is less intrusive than
the old loud beep and can still be located accurately with cross-correlation.
The test must:

- use the production session and fan-out code rather than a second protocol
  implementation;
- send the exact same numbered PCM frames to every receiver;
- require an opt-in environment variable and explicit receiver addresses;
- default to a conservative volume and never run in normal CI; and
- record the seed, group ID, receiver identities, protocols, and test duration.

### Acoustic measurement

Record the receivers at the same time. For two receivers, place one microphone
near each speaker and use a stereo USB interface. For larger groups, use a
multi-channel interface or measure every receiver pair against one stable
reference receiver. Keep microphones equidistant from their speaker or subtract
the known sound-travel difference.

Add a small offline analysis tool that cross-correlates each recorded channel
with the original seeded signal and emits CSV or JSON containing:

- initial acoustic offset relative to the reference receiver;
- offset for every noise burst;
- median, 95th percentile, and maximum skew;
- drift slope in samples per minute and parts per million; and
- missing or discontinuous regions.

White noise by itself is useful for listening tests, but a seeded and enveloped
signal is important here so results are repeatable and machine measurable.

### Sender instrumentation

Before changing behavior, add structured debug events for:

- group ID, receiver identity, address, and chosen protocol;
- SETUP and RECORD completion times;
- the shared target time and each session's RTP timestamp at that target;
- capture frame index, RTP timestamp, and monotonic send time of the first packet;
- per-receiver queue depth, encoding time, socket-send time, and late packets;
- advertised/returned receiver latency where available;
- timing-request count, round-trip/offset samples, sync packets, retransmissions,
  and loss; and
- join/leave events and the exact group frame used for a join.

Use `SystemClock.elapsedRealtimeNanos()` for durations and ordering. Wall-clock
Unix/NTP time is required on the wire, but it should be derived from one stable
wall-clock-to-monotonic anchor instead of repeatedly trusting
`System.currentTimeMillis()`, which can jump. Keep these details out of the
normal UI log; export them only when debug information is enabled.

## Investigation matrix

Establish a v0.4.9 baseline before adding compensation. Run at least the
following cases:

| Group | Startup runs | Sustained run | Live changes |
| --- | ---: | ---: | --- |
| One AirPlay 1 receiver | 20 | 10 min | reconnect |
| One AirPlay 2 receiver | 20 | 10 min | reconnect |
| Two AirPlay 1 receivers | 20 | 10 and 60 min | add/remove second |
| Two AirPlay 2 receivers | 20 | 10 and 60 min | add/remove second |
| Mixed AirPlay 1 + 2 | 20 | 10 and 60 min | add/remove either |
| Three receivers | 10 | 10 min | add/remove third |

Repeat the important pairs on an idle Wi-Fi network and while the network is
busy. The Symfonisk AirPlay 1 plus Sony Bravia AirPlay 2 pair is the first real
mixed-protocol reference. A software receiver is useful for deterministic
tests, but hardware recordings decide whether the result is actually good.

For each run, distinguish these failure classes:

- **Startup offset:** skew is present immediately but remains constant.
- **Clock drift:** skew grows or shrinks during playback.
- **Packet scheduling:** skew or dropouts correlate with sender queueing,
  encoding, retransmission, or Wi-Fi load.
- **Receiver variability:** the fixed offset changes between otherwise
  identical cold starts.
- **Join disturbance:** an existing receiver jumps or pauses when another joins.

## Implementation stages

### 1. Stable group media clock

Introduce a `GroupTimeline` owned by the capture service. Give every captured
PCM chunk an absolute 64-bit frame index. Keep RTP wrapping and receiver-local
sequence numbers inside each session, but derive their timestamps from that
shared frame index.

Represent time at sample or NTP-fraction precision rather than truncating the
mapping to integer milliseconds. Anchor the group once using wall time plus a
monotonic timestamp, then advance it from frame counts and monotonic elapsed
time.

Vary the initial lead time (250, 500, 1000, and 2000 ms) during measurement.
Choose enough lead time to prime the slowest receiver consistently; do not
assume the current 250 ms is sufficient merely because SETUP succeeded.

### 2. Remove fan-out head-of-line blocking

Packetize captured audio once into numbered group frames. Give each receiver a
small bounded sender queue and worker so a slow receiver cannot delay the rest
of the group. All workers must retain the same group frame identity and target
time.

Measure this before and after the change. Bound the queues so a broken receiver
is disconnected instead of accumulating unbounded latency. Keep encoding reuse
where the wire format is identical; otherwise encode per protocol, not
unnecessarily per receiver.

### 3. Correct fixed receiver render delay

If acoustic tests show stable startup offsets, store a correction keyed by
stable receiver identity and protocol. The same device may have different
AirPlay 1 and AirPlay 2 delays.

For a group acoustic target time, map each receiver's frame to:

```text
receiver network target = group acoustic target - calibrated render delay
```

In practice, preserve sufficient startup lead and delay faster paths rather
than starving a slower receiver. Apply correction at the RTP-to-NTP mapping;
do not alter the shared captured PCM. First keep this behind a debug flag and
compare repeated cold starts before persisting it automatically.

Receiver-advertised latency can seed the estimate, but acoustic measurement is
the authority unless testing shows the advertised value is reliable.

### 4. Correct drift gradually

First verify whether the existing periodic sync packets already keep long-term
skew bounded. If not, estimate each receiver clock against the stable group
clock and apply only gradual correction:

1. Prefer small phase/rate adjustments in the receiver's RTP/NTP mapping if the
   receiver follows updated sync packets.
2. If a receiver ignores those adjustments, add a bounded per-receiver
   fractional resampler or rare sample insertion/removal at safe boundaries.
3. Never jump a timestamp or discard a large block of live audio to chase a
   single noisy timing sample.

Limit correction rate, reject outliers, and expose both the raw estimate and
applied correction in debug data. Test volume changes and metadata updates to
ensure they do not affect the clock.

### 5. Join and leave without restarting the group

When adding a receiver during playback:

1. Keep every existing session's mapping unchanged.
2. Complete the new receiver's handshake while capture continues.
3. Select a future group frame with enough lead for that receiver.
4. Prefill it from a short bounded history buffer, then map that frame to the
   already-running group timeline plus its calibrated correction.
5. Start forwarding live frames once it catches the join point.

Removing a receiver should only close that session and must not change the
group timeline. If a receiver cannot join safely, report the failure without
disturbing the active group.

### 6. Manual group calibration

Provide manual calibration before attempting microphone-based automatic
measurement. Let the user choose the receiver heard latest as the reference,
then delay each faster receiver in 5 ms steps while listening. Save the
reference and corrections for the exact set of stable receiver identities and
protocols, because a device's AirPlay 1 and AirPlay 2 buffering may differ.

Only non-negative delays are needed: choosing the naturally slowest receiver as
the reference means every other path can be held back safely. Apply changes
live when a slider is released, with enough scheduling lead to avoid starving a
receiver.

A later group status view can show `syncing`, `in sync`, `drifting`, or
`unreachable`, but it should be based on measurements rather than connection
state alone. Normal playback should remain as simple as selecting receivers and
pressing Stream.

## Delivery checkpoints

Keep each checkpoint independently testable and avoid combining protocol fixes
with UI work:

1. Structured timing instrumentation with no playback behavior change.
2. Opt-in seeded-noise integration test and acoustic analysis tool.
3. Stable, higher-resolution `GroupTimeline` plus regression tests.
4. Non-blocking per-receiver send queues.
5. Saved manual group timing with an explicit reference receiver.
6. Automatic fixed-delay estimation only if it provides value beyond manual
   tuning.
7. Drift correction, only if long-run measurements demonstrate a need.
8. Timeline-preserving live join/remove and optional sync-health UI.

Every checkpoint must retain the single-receiver startup tests and include unit
tests for RTP wraparound, independent RTP origins, monotonic clock conversion,
queue overflow, and joining at a future group frame.

## Definition of done

- The selected hardware matrix meets the agreed startup-skew and drift targets.
- Single-receiver AirPlay 1 and AirPlay 2 startup, sound, and volume do not
  regress.
- Existing receivers continue uninterrupted when another receiver joins or
  leaves.
- Backgrounding the app, metadata changes, and volume changes do not move the
  media timeline.
- Hardware audio tests remain explicit, low-volume, and disabled by default.
- Normal logs remain quiet; a debug export contains enough data to reproduce a
  synchronization result.

## Questions the measurements must answer

- How large and how repeatable is the Symfonisk AirPlay 1 to Bravia AirPlay 2
  acoustic offset?
- Do offsets remain stable across receiver reboots and new sessions?
- Are returned or advertised latency values useful enough to avoid manual
  calibration?
- Do receivers follow small corrections in later sync packets?
- Is current sequential encoding/sending measurably late with two or three
  receivers?
- Does Android's capture timestamp stay stable relative to the monotonic clock?
- How many receivers can the phone serve before CPU, encoder, or Wi-Fi load
  creates late packets?
