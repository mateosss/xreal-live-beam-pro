# XREAL Beam Pro — Stereo Camera Passthrough

Android app for the XREAL Beam Pro. It opens both rear cameras, places their feeds side by side
(left eye | right eye) and shows the result full-screen on connected XREAL Air glasses, for
real-time 3D passthrough.

Built from the spec in [`docs/xreal_beampro_stereo_passthrough_spec.md`](docs/xreal_beampro_stereo_passthrough_spec.md)
(from [gabriellupu/xreal-glasses-live-beam-pro](https://github.com/gabriellupu/xreal-glasses-live-beam-pro)).

Kotlin · Camera2 · OpenGL ES 2.0 · minSdk 31 / targetSdk 34 · no XREAL SDK required.

## Build & install

Requires JDK 17 and the Android SDK (platform 34), with `sdk.dir` set in `local.properties`
or `ANDROID_HOME` set.

```sh
./gradlew assembleDebug
adb connect <BeamPro_IP>:5555          # wireless debugging, or use USB
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.xreal.stereoview/.MainActivity
```

Connect the glasses before or after launch; the app reacts to the display being plugged in or
removed. For true 3D, the glasses must be in their 3D/SBS mode (3840×1080), so each eye sees
one half. In 2D mode both eyes see the whole frame.

## How it works

| Piece | File |
|---|---|
| Picks the camera pair and the left/right order, with a fallback chain | `camera/CameraUtils.kt` |
| Opens the cameras and streams into two SurfaceTextures, stepping down sizes on failure | `camera/CameraController.kt` |
| Pairs left/right frames before drawing | `camera/FrameSynchronizer.kt` |
| Side-by-side compositing per eye: rotation, aspect, convergence, depth | `render/StereoRenderer.kt`, `render/StereoShader.kt` |
| Full-screen output on the glasses display | `render/StereoPresentation.kt`, `util/DisplayUtils.kt` |
| Touchscreen controls and DataStore settings | `ui/ControlsOverlay.kt`, `ui/SettingsViewModel.kt` |

**Camera selection.** The app tries each option below in order. If one can't be opened or
configured, it falls back to the next:

1. **Logical multi-camera** that contains two back physical cameras with matching focal
   lengths. This gives one session with physical-camera outputs, and the hardware can sync it.
2. **Concurrent pair** listed in `CameraManager.concurrentCameraIds`.
3. **Dual open** of two matching back cameras, even though the device doesn't list them as
   concurrent.
4. **Mono**: one camera feeds both eyes (2D), so you still get passthrough.

Left and right come from `LENS_POSE_TRANSLATION`, converted to the current screen orientation.
If the pose isn't available, the ID order is used. **Swap L/R** corrects either case.

**Output.** If the glasses show up as a presentation display, the stereo view goes there and
the phone screen keeps only the controls. Otherwise the view is drawn full-screen on the phone,
which the glasses mirror. In that mode, tap the view to hide or show the control panel.
*More → Output* forces mirrored mode.

**Sync.** A frame is drawn once both eyes have a new frame. If the second eye's frame doesn't
arrive within the threshold (33 ms by default), the frame is drawn anyway, so a slow camera
never freezes the other eye. The HUD shows each eye's fps, the left/right timestamp gap, and
how many frame pairs exceeded the threshold.

## Controls

| Control | Effect |
|---|---|
| Pause / Play | Closes / reopens the cameras |
| Swap L/R | Swaps which camera feeds which eye |
| Fill / Fit / Stretch | Fits each camera image into its half: crop, letterbox, or stretch (use Stretch for half-SBS displays) |
| Convergence | ±50 px horizontal shift per eye, in opposite directions |
| Depth | Zooms each eye 1–2× about its center, which also scales disparity. Pinch on the touchscreen does the same |
| Brightness | Exposure compensation, using the camera's own range |
| HUD | Frame-rate and sync statistics |
| Info | Dump of all cameras (poses, focal lengths, sync capability, concurrent sets) and displays |
| More | Output mode, image rotation override, max resolution, sync wait, reset adjustments |

All settings persist through DataStore.

## Not implemented / caveats

- **Untested on Beam Pro hardware.** The project builds and passes lint. How the Beam Pro
  exposes its two cameras (logical, concurrent or neither) and the glasses display is only
  known on the device. The **Info** dialog shows exactly that. If the image is rotated wrong,
  use *More → Image rotation*. If depth is inverted, use *Swap L/R*.
- No separate floating overlay window: a `TYPE_APPLICATION_OVERLAY` window would be mirrored
  to the glasses along with everything else, so the controls go on the phone screen instead
  (presentation mode) or on a hideable panel. This is why the app requests only the `CAMERA`
  permission.
- Stretch goals from the spec are not built: SBS recording, automatic convergence, L/R color
  matching, and lens-distortion correction from `LENS_INTRINSIC_CALIBRATION`.
