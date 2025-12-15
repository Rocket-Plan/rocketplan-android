## FLIR preview troubleshooting

If the FLIR preview renders a black screen even though logs show `onDrawFrame` is running, ensure these settings are applied:

- Use GLES 2 for the FLIR pipeline (forcing ES2 avoided blank output on some devices where ES3 was selected).
- Configure the GLSurfaceView holder as translucent (`holder.setFormat(PixelFormat.TRANSLUCENT)`) and keep default z-order so UI controls can draw above the preview. If you encounter black preview issues again, temporarily try `setZOrderOnTop(true)` to confirm a z-order interaction, but revert to default once verified.
- Keep the surface config with an 8-8-8-8 RGBA config (as in `FlirCameraController.attachSurface`).

These changes are implemented in `app/src/flir/java/com/example/rocketplan_android/thermal/FlirCameraController.kt`. They resolved the black-preview issue on the test ACE device while frames were being rendered. If the preview goes black again, re-verify these flags and the build variant (FLIR build flavor).

Overlaying controls:
- `GLSurfaceView` with `setZOrderOnTop(true)` is the most reliable for preview on this device, but controls cannot be drawn above it.
- Switching to `setZOrderMediaOverlay(true)` or default z-order allows overlays but has been unreliable/blank on this device.
- If overlays on top of the preview are required, consider swapping to a `TextureView`, which participates in normal view stacking.
