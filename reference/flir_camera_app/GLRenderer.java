package s2;

import A8.C0078h;
import Q.T;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import c2.AbstractC0946a;
import java.nio.Buffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import r2.y;

/* loaded from: classes.dex */
public final class j implements GLSurfaceView.Renderer, InterfaceC2332c {

    /* renamed from: a, reason: collision with root package name */
    public final i f22970a;

    /* renamed from: d, reason: collision with root package name */
    public final float[] f22973d;

    /* renamed from: e, reason: collision with root package name */
    public final float[] f22974e;

    /* renamed from: f, reason: collision with root package name */
    public final float[] f22975f;

    /* renamed from: t, reason: collision with root package name */
    public float f22976t;

    /* renamed from: u, reason: collision with root package name */
    public float f22977u;

    /* renamed from: x, reason: collision with root package name */
    public final /* synthetic */ k f22980x;

    /* renamed from: b, reason: collision with root package name */
    public final float[] f22971b = new float[16];

    /* renamed from: c, reason: collision with root package name */
    public final float[] f22972c = new float[16];

    /* renamed from: v, reason: collision with root package name */
    public final float[] f22978v = new float[16];

    /* renamed from: w, reason: collision with root package name */
    public final float[] f22979w = new float[16];

    public j(k kVar, i iVar) {
        this.f22980x = kVar;
        float[] fArr = new float[16];
        this.f22973d = fArr;
        float[] fArr2 = new float[16];
        this.f22974e = fArr2;
        float[] fArr3 = new float[16];
        this.f22975f = fArr3;
        this.f22970a = iVar;
        Matrix.setIdentityM(fArr, 0);
        Matrix.setIdentityM(fArr2, 0);
        Matrix.setIdentityM(fArr3, 0);
        this.f22977u = 3.1415927f;
    }

    @Override // s2.InterfaceC2332c
    public final synchronized void a(float[] fArr, float f10) {
        float[] fArr2 = this.f22973d;
        System.arraycopy(fArr, 0, fArr2, 0, fArr2.length);
        float f11 = -f10;
        this.f22977u = f11;
        Matrix.setRotateM(this.f22974e, 0, -this.f22976t, (float) Math.cos(f11), (float) Math.sin(this.f22977u), 0.0f);
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public final void onDrawFrame(GL10 gl10) {
        Object objZ;
        synchronized (this) {
            Matrix.multiplyMM(this.f22979w, 0, this.f22973d, 0, this.f22975f, 0);
            Matrix.multiplyMM(this.f22978v, 0, this.f22974e, 0, this.f22979w, 0);
        }
        Matrix.multiplyMM(this.f22972c, 0, this.f22971b, 0, this.f22978v, 0);
        i iVar = this.f22970a;
        float[] fArr = this.f22972c;
        GLES20.glClear(16384);
        try {
            AbstractC0946a.e();
        } catch (c2.f e5) {
            AbstractC0946a.n("SceneRenderer", "Failed to draw a frame", e5);
        }
        if (iVar.f22957a.compareAndSet(true, false)) {
            SurfaceTexture surfaceTexture = iVar.f22966w;
            surfaceTexture.getClass();
            surfaceTexture.updateTexImage();
            try {
                AbstractC0946a.e();
            } catch (c2.f e8) {
                AbstractC0946a.n("SceneRenderer", "Failed to draw a frame", e8);
            }
            if (iVar.f22958b.compareAndSet(true, false)) {
                Matrix.setIdentityM(iVar.f22963t, 0);
            }
            long timestamp = iVar.f22966w.getTimestamp();
            C0078h c0078h = iVar.f22961e;
            synchronized (c0078h) {
                objZ = c0078h.z(timestamp, false);
            }
            Long l4 = (Long) objZ;
            if (l4 != null) {
                T t9 = iVar.f22960d;
                float[] fArr2 = iVar.f22963t;
                float[] fArr3 = (float[]) ((C0078h) t9.f7503d).B(l4.longValue());
                if (fArr3 != null) {
                    float f10 = fArr3[0];
                    float f11 = -fArr3[1];
                    float f12 = -fArr3[2];
                    float length = Matrix.length(f10, f11, f12);
                    float[] fArr4 = (float[]) t9.f7502c;
                    if (length != 0.0f) {
                        Matrix.setRotateM(fArr4, 0, (float) Math.toDegrees(length), f10 / length, f11 / length, f12 / length);
                    } else {
                        Matrix.setIdentityM(fArr4, 0);
                    }
                    if (!t9.f7500a) {
                        T.c((float[]) t9.f7501b, (float[]) t9.f7502c);
                        t9.f7500a = true;
                    }
                    Matrix.multiplyMM(fArr2, 0, (float[]) t9.f7501b, 0, (float[]) t9.f7502c, 0);
                }
            }
            C2335f c2335f = (C2335f) iVar.f22962f.B(timestamp);
            if (c2335f != null) {
                C2336g c2336g = iVar.f22959c;
                c2336g.getClass();
                if (C2336g.b(c2335f)) {
                    c2336g.f22948a = c2335f.f22944c;
                    c2336g.f22949b = new C0078h(c2335f.f22942a.f22941a[0]);
                    if (!c2335f.f22945d) {
                        C0078h c0078h2 = c2335f.f22943b.f22941a[0];
                        float[] fArr5 = (float[]) c0078h2.f805d;
                        int length2 = fArr5.length;
                        AbstractC0946a.k(fArr5);
                        AbstractC0946a.k((float[]) c0078h2.f806e);
                    }
                }
            }
        }
        Matrix.multiplyMM(iVar.f22964u, 0, fArr, 0, iVar.f22963t, 0);
        C2336g c2336g2 = iVar.f22959c;
        int i = iVar.f22965v;
        float[] fArr6 = iVar.f22964u;
        C0078h c0078h3 = c2336g2.f22949b;
        if (c0078h3 == null) {
            return;
        }
        int i3 = c2336g2.f22948a;
        GLES20.glUniformMatrix3fv(c2336g2.f22952e, 1, false, i3 == 1 ? C2336g.f22946j : i3 == 2 ? C2336g.f22947k : C2336g.i, 0);
        GLES20.glUniformMatrix4fv(c2336g2.f22951d, 1, false, fArr6, 0);
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(36197, i);
        GLES20.glUniform1i(c2336g2.f22955h, 0);
        try {
            AbstractC0946a.e();
        } catch (c2.f e10) {
            Log.e("ProjectionRenderer", "Failed to bind uniforms", e10);
        }
        GLES20.glVertexAttribPointer(c2336g2.f22953f, 3, 5126, false, 12, (Buffer) c0078h3.f805d);
        try {
            AbstractC0946a.e();
        } catch (c2.f e11) {
            Log.e("ProjectionRenderer", "Failed to load position data", e11);
        }
        GLES20.glVertexAttribPointer(c2336g2.f22954g, 2, 5126, false, 8, (Buffer) c0078h3.f806e);
        try {
            AbstractC0946a.e();
        } catch (c2.f e12) {
            Log.e("ProjectionRenderer", "Failed to load texture data", e12);
        }
        GLES20.glDrawArrays(c0078h3.f804c, 0, c0078h3.f803b);
        try {
            AbstractC0946a.e();
        } catch (c2.f e13) {
            Log.e("ProjectionRenderer", "Failed to render", e13);
        }
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public final void onSurfaceChanged(GL10 gl10, int i, int i3) {
        GLES20.glViewport(0, 0, i, i3);
        float f10 = i / i3;
        Matrix.perspectiveM(this.f22971b, 0, f10 > 1.0f ? (float) (Math.toDegrees(Math.atan(Math.tan(Math.toRadians(45.0d)) / f10)) * 2.0d) : 90.0f, f10, 0.1f, 100.0f);
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public final synchronized void onSurfaceCreated(GL10 gl10, EGLConfig eGLConfig) {
        k kVar = this.f22980x;
        kVar.f22985e.post(new y(1, kVar, this.f22970a.b()));
    }
}
