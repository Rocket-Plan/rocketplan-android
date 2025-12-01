# FLIR Earhart Camera App - Decompiled Reference

Decompiled from `com.flir.earhart.camera.release` APK for reference on how FLIR implements their thermal camera streaming.

## Key Findings

### 1. glSetupPipeline Parameter
They always call `glSetupPipeline(stream, false)` - the second parameter is `false`:
```java
// From C1023b.java and ThermalStreamViewModel
((Camera) obj2).glSetupPipeline((Stream) r12, false);
```

### 2. EGL Context Version
- Custom GLSurfaceView (`ThermalGLSurfaceView.java`): Uses version **2**
- StreamService (`StreamService.java`): Uses version **3**

```java
// ThermalGLSurfaceView.java
setEGLContextClientVersion(2);

// StreamService.java
gLSurfaceViewQ.setEGLContextClientVersion(3);
```

### 3. glTeardownPipeline Has 500ms Delay
```java
// Camera.java
public void glTeardownPipeline() throws InterruptedException {
    if (this.glFrameRendererAddr != 0) {
        Thread.sleep(500L);  // <-- 500ms wait before teardown!
        glTeardownPipelineNative(this.glFrameRendererAddr);
        this.glFrameRendererAddr = 0L;
    }
}
```

### 4. Stream Selection - Find Thermal Stream
```java
// ThermalStreamViewModel$connectCamera$1$1$1.java
Iterator<T> it = streams.iterator();
while (it.hasNext()) {
    next = it.next();
    if (((Stream) next).isThermal()) {
        break;
    }
}
stream = (Stream) next;
```

### 5. ThermalStreamer for Non-GL Path
They use `ThermalStreamer` class for non-OpenGL rendering:
```java
j0Var.f25724v = new ThermalStreamer(stream);
thermalStreamer.withThermalImage(new H(j0Var, 3));
```

### 6. Safety Checks
They always check `glFrameRendererAddr != 0` before calling GL methods:
```java
public boolean glOnDrawFrame() {
    long j10 = this.glFrameRendererAddr;
    if (j10 != 0) {
        return glOnDrawFrameNative(j10);
    }
    ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null...");
    return false;
}
```

## Files

| File | Description |
|------|-------------|
| `Camera.java` | FLIR SDK Camera class with all GL methods |
| `ThermalStreamViewModel.java` | Main ViewModel for camera connection |
| `ThermalStreamViewModel$connectCamera$1$1$1.java` | Camera connection coroutine |
| `StreamService.java` | Service managing streams and GL surface |
| `ThermalGLSurfaceView.java` | Custom GLSurfaceView (EGL v2) |
| `GLRenderer.java` | GLSurfaceView.Renderer implementation |
| `StreamCallback.java` | Stream event callbacks |
| `PaletteController.java` | Palette and isotherm management |
| `C1023b.java` | Lambda containing glSetupPipeline call |

## Notes

- These are decompiled/obfuscated files - variable names are not original
- Package names like `S2`, `X4`, `s4` are obfuscated
- This is for reference only - do not include in production builds
