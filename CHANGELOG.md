# Changelog

## v0.3.2

- Fix audio capture startup on current Android targets by registering the required `MediaProjection.Callback` before starting playback capture.
- Keep Android's required playback-capture audio permission flow, but avoid asking again once permission is already granted and clarify the denial message.
- Release `AudioRecord` more defensively when capture stops or startup fails.
