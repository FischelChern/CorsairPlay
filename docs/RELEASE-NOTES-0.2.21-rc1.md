# Lincoln/Ford 0.2.21-rc1

This is a release candidate on top of 0.2.20. Everything from the previous build is kept.

## New: lower music while navigation speaks

Settings -> Audio routing -> **Lower music during navigation**. Off by default.

- Detects an actual CarPlay navigation prompt from the guidance stream's PCM, not from the
  per-second route metadata, so merely having a route active never ducks the music.
- Ducks only CarPlay music to about -12 dB with a ~300 ms ramp, holds through the pauses
  between sentences, and ramps back up after the prompt ends.
- Calls (including a call played on the loudspeaker) and Siri are never ducked.
- Applies immediately to a running session; no reconnect is needed and the head unit's own
  stream volumes are untouched.
- The navigation guidance attenuation slider still works independently.

## Notes

- Package name and signing key are unchanged, so this installs over 0.2.20 and keeps all settings.
- Vehicle identity, cluster forwarding and the Lincoln/Ford video tuning are unchanged.
