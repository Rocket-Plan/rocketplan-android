package s2;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import java.util.concurrent.CopyOnWriteArrayList;
import r2.q;

/* loaded from: classes.dex */
public final class k extends GLSurfaceView {

    /* renamed from: a, reason: collision with root package name */
    public final CopyOnWriteArrayList f22981a;

    /* renamed from: b, reason: collision with root package name */
    public final SensorManager f22982b;

    /* renamed from: c, reason: collision with root package name */
    public final Sensor f22983c;

    /* renamed from: d, reason: collision with root package name */
    public final C2333d f22984d;

    /* renamed from: e, reason: collision with root package name */
    public final Handler f22985e;

    /* renamed from: f, reason: collision with root package name */
    public final i f22986f;

    /* renamed from: t, reason: collision with root package name */
    public SurfaceTexture f22987t;

    /* renamed from: u, reason: collision with root package name */
    public Surface f22988u;

    /* renamed from: v, reason: collision with root package name */
    public boolean f22989v;

    /* renamed from: w, reason: collision with root package name */
    public boolean f22990w;

    /* renamed from: x, reason: collision with root package name */
    public boolean f22991x;

    public k(Context context) {
        super(context, null);
        this.f22981a = new CopyOnWriteArrayList();
        this.f22985e = new Handler(Looper.getMainLooper());
        Object systemService = context.getSystemService("sensor");
        systemService.getClass();
        SensorManager sensorManager = (SensorManager) systemService;
        this.f22982b = sensorManager;
        Sensor defaultSensor = sensorManager.getDefaultSensor(15);
        this.f22983c = defaultSensor == null ? sensorManager.getDefaultSensor(11) : defaultSensor;
        i iVar = new i();
        this.f22986f = iVar;
        j jVar = new j(this, iVar);
        View.OnTouchListener lVar = new l(context, jVar);
        WindowManager windowManager = (WindowManager) context.getSystemService("window");
        windowManager.getClass();
        this.f22984d = new C2333d(windowManager.getDefaultDisplay(), lVar, jVar);
        this.f22989v = true;
        setEGLContextClientVersion(2);
        setRenderer(jVar);
        setOnTouchListener(lVar);
    }

    public final void a() {
        boolean z4 = this.f22989v && this.f22990w;
        Sensor sensor = this.f22983c;
        if (sensor == null || z4 == this.f22991x) {
            return;
        }
        C2333d c2333d = this.f22984d;
        SensorManager sensorManager = this.f22982b;
        if (z4) {
            sensorManager.registerListener(c2333d, sensor, 0);
        } else {
            sensorManager.unregisterListener(c2333d);
        }
        this.f22991x = z4;
    }

    public InterfaceC2330a getCameraMotionListener() {
        return this.f22986f;
    }

    public q getVideoFrameMetadataListener() {
        return this.f22986f;
    }

    public Surface getVideoSurface() {
        return this.f22988u;
    }

    @Override // android.opengl.GLSurfaceView, android.view.SurfaceView, android.view.View
    public final void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        this.f22985e.post(new B0.d(this, 28));
    }

    @Override // android.opengl.GLSurfaceView
    public final void onPause() {
        this.f22990w = false;
        a();
        super.onPause();
    }

    @Override // android.opengl.GLSurfaceView
    public final void onResume() {
        super.onResume();
        this.f22990w = true;
        a();
    }

    public void setDefaultStereoMode(int i) {
        this.f22986f.f22967x = i;
    }

    public void setUseSensorRotation(boolean z4) {
        this.f22989v = z4;
        a();
    }
}
