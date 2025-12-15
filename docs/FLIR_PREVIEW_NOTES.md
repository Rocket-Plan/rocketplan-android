## FLIR preview troubleshooting

If the FLIR preview renders a black screen even though logs show `onDrawFrame` is running, ensure these settings are applied:

- Use GLES 2 for the FLIR pipeline (forcing ES2 avoided blank output on some devices where ES3 was selected).
- Configure the GLSurfaceView holder as translucent and set z-order based on whether overlays are showing:
  - Default (reliable preview): `setZOrderOnTop(true)` with `holder.setFormat(PixelFormat.TRANSLUCENT)`.
  - When showing an in-layout overlay (e.g., loading scrim), temporarily switch to `setZOrderMediaOverlay(true)` so the overlay can draw on top. `FlirCaptureFragment` does this by calling `setOverlayFriendlyMode(state.isProcessing)`.
- Keep the surface config with an 8-8-8-8 RGBA config (as in `FlirCameraController.attachSurface`).

These changes are implemented in `app/src/flir/java/com/example/rocketplan_android/thermal/FlirCameraController.kt`. They resolved the black-preview issue on the test ACE device while frames were being rendered. If the preview goes black again, re-verify these flags and the build variant (FLIR build flavor).
