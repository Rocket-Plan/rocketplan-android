package x4;

import B.AbstractC0109q;
import Q.C0508c;
import Q.C0525k0;
import Q.C0527l0;
import Q.C0533o0;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.opengl.GLException;
import android.opengl.GLSurfaceView;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Size;
import androidx.lifecycle.C0903t;
import c3.RunnableC0951E;
import com.flir.earhart.common.composable.C1032q;
import com.flir.thermalsdk.androidsdk.image.BitmapAndroid;
import com.flir.thermalsdk.image.CameraInformation;
import com.flir.thermalsdk.image.DisplaySettings;
import com.flir.thermalsdk.image.FlipType;
import com.flir.thermalsdk.image.ImageBuffer;
import com.flir.thermalsdk.image.ImageColorizer;
import com.flir.thermalsdk.image.JavaImageBuffer;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.image.ThermalImageFile;
import com.flir.thermalsdk.image.ThermalValue;
import com.flir.thermalsdk.image.fusion.Fusion;
import com.flir.thermalsdk.image.fusion.FusionMode;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.remote.AppCoreResourceTreeService;
import com.flir.thermalsdk.live.remote.Calibration;
import com.flir.thermalsdk.live.remote.Property;
import com.flir.thermalsdk.live.remote.RemoteControl;
import com.flir.thermalsdk.live.remote.TemperatureRange;
import com.flir.thermalsdk.live.streaming.Stream;
import com.flir.thermalsdk.live.streaming.ThermalStreamer;
import com.flir.thermalsdk.utils.Pair;
import f9.E0;
import f9.G0;
import g4.C1443b;
import g6.C1451b;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Timer;
import java.util.concurrent.CancellationException;
import javax.microedition.khronos.opengles.GL10;
import m6.u0;
import n4.InterfaceC1968a;
import o9.C2089c;
import o9.InterfaceC2087a;
import p9.AbstractC2157b;
import q3.AbstractC2176a;
import s.C2323z;
import t4.EnumC2424l;
import t4.EnumC2427o;
import t4.InterfaceC2415c;
import t4.InterfaceC2417e;
import v1.C2650t;
import y7.EnumC2851a;
import z7.AbstractC2886c;

/* loaded from: classes.dex */
public final class j0 implements InterfaceC2417e {

    /* renamed from: N0, reason: collision with root package name */
    public static final String f25644N0;

    /* renamed from: A, reason: collision with root package name */
    public int f25645A;

    /* renamed from: A0, reason: collision with root package name */
    public final i9.T f25646A0;

    /* renamed from: B, reason: collision with root package name */
    public int f25647B;

    /* renamed from: B0, reason: collision with root package name */
    public final ArrayList f25648B0;

    /* renamed from: C, reason: collision with root package name */
    public volatile boolean f25649C;

    /* renamed from: C0, reason: collision with root package name */
    public final C0527l0 f25650C0;

    /* renamed from: D, reason: collision with root package name */
    public volatile boolean f25651D;

    /* renamed from: D0, reason: collision with root package name */
    public final C0527l0 f25652D0;

    /* renamed from: E, reason: collision with root package name */
    public volatile boolean f25653E;

    /* renamed from: E0, reason: collision with root package name */
    public final C0533o0 f25654E0;

    /* renamed from: F, reason: collision with root package name */
    public volatile long f25655F;

    /* renamed from: F0, reason: collision with root package name */
    public final i9.k0 f25656F0;

    /* renamed from: G, reason: collision with root package name */
    public final C0533o0 f25657G;

    /* renamed from: G0, reason: collision with root package name */
    public final i9.U f25658G0;

    /* renamed from: H, reason: collision with root package name */
    public ThermalImageFile f25659H;

    /* renamed from: H0, reason: collision with root package name */
    public final C0525k0 f25660H0;

    /* renamed from: I, reason: collision with root package name */
    public ImageColorizer f25661I;

    /* renamed from: I0, reason: collision with root package name */
    public Bitmap f25662I0;

    /* renamed from: J, reason: collision with root package name */
    public ThermalImageFile f25663J;

    /* renamed from: J0, reason: collision with root package name */
    public final C0533o0 f25664J0;

    /* renamed from: K, reason: collision with root package name */
    public Uri f25665K;

    /* renamed from: K0, reason: collision with root package name */
    public final C0533o0 f25666K0;
    public boolean L;

    /* renamed from: L0, reason: collision with root package name */
    public final C2089c f25667L0;

    /* renamed from: M, reason: collision with root package name */
    public ImageColorizer f25668M;

    /* renamed from: M0, reason: collision with root package name */
    public final S f25669M0;

    /* renamed from: N, reason: collision with root package name */
    public boolean f25670N;

    /* renamed from: O, reason: collision with root package name */
    public int f25671O;

    /* renamed from: P, reason: collision with root package name */
    public final i9.Z f25672P;

    /* renamed from: Q, reason: collision with root package name */
    public final i9.Z f25673Q;

    /* renamed from: R, reason: collision with root package name */
    public final i9.Z f25674R;

    /* renamed from: S, reason: collision with root package name */
    public final i9.Z f25675S;

    /* renamed from: T, reason: collision with root package name */
    public com.flir.earhart.common.viewModel.b f25676T;

    /* renamed from: U, reason: collision with root package name */
    public Q3.a f25677U;

    /* renamed from: V, reason: collision with root package name */
    public com.flir.earhart.common.viewModel.b f25678V;

    /* renamed from: W, reason: collision with root package name */
    public final C0533o0 f25679W;

    /* renamed from: X, reason: collision with root package name */
    public final C0533o0 f25680X;

    /* renamed from: Y, reason: collision with root package name */
    public final C0533o0 f25681Y;

    /* renamed from: Z, reason: collision with root package name */
    public Q f25682Z;

    /* renamed from: a, reason: collision with root package name */
    public final InterfaceC2415c f25683a;

    /* renamed from: a0, reason: collision with root package name */
    public boolean f25684a0;

    /* renamed from: b, reason: collision with root package name */
    public final y4.g f25685b;

    /* renamed from: b0, reason: collision with root package name */
    public Application f25686b0;

    /* renamed from: c, reason: collision with root package name */
    public final t4.N f25687c;

    /* renamed from: c0, reason: collision with root package name */
    public Context f25688c0;

    /* renamed from: d, reason: collision with root package name */
    public final y4.h f25689d;

    /* renamed from: d0, reason: collision with root package name */
    public String f25690d0;

    /* renamed from: e, reason: collision with root package name */
    public final y4.f f25691e;

    /* renamed from: e0, reason: collision with root package name */
    public String f25692e0;

    /* renamed from: f, reason: collision with root package name */
    public final h4.a f25693f;

    /* renamed from: f0, reason: collision with root package name */
    public Uri f25694f0;

    /* renamed from: g, reason: collision with root package name */
    public final h4.h f25695g;

    /* renamed from: g0, reason: collision with root package name */
    public boolean f25696g0;

    /* renamed from: h, reason: collision with root package name */
    public final y4.d f25697h;

    /* renamed from: h0, reason: collision with root package name */
    public H7.k f25698h0;
    public final y4.e i;

    /* renamed from: i0, reason: collision with root package name */
    public InterfaceC1968a f25699i0;

    /* renamed from: j, reason: collision with root package name */
    public final t4.M f25700j;

    /* renamed from: j0, reason: collision with root package name */
    public int[] f25701j0;

    /* renamed from: k0, reason: collision with root package name */
    public int[] f25703k0;

    /* renamed from: l, reason: collision with root package name */
    public Stream f25704l;

    /* renamed from: l0, reason: collision with root package name */
    public IntBuffer f25705l0;

    /* renamed from: m, reason: collision with root package name */
    public Camera f25706m;

    /* renamed from: m0, reason: collision with root package name */
    public final Object f25707m0;

    /* renamed from: n, reason: collision with root package name */
    public boolean f25708n;

    /* renamed from: n0, reason: collision with root package name */
    public final Object f25709n0;

    /* renamed from: o0, reason: collision with root package name */
    public final Object f25711o0;

    /* renamed from: p, reason: collision with root package name */
    public boolean f25712p;

    /* renamed from: p0, reason: collision with root package name */
    public int f25713p0;

    /* renamed from: q0, reason: collision with root package name */
    public Q f25715q0;

    /* renamed from: r, reason: collision with root package name */
    public Q f25716r;

    /* renamed from: r0, reason: collision with root package name */
    public Q f25717r0;

    /* renamed from: s, reason: collision with root package name */
    public E0 f25718s;

    /* renamed from: s0, reason: collision with root package name */
    public double f25719s0;

    /* renamed from: t, reason: collision with root package name */
    public final k9.c f25720t;

    /* renamed from: t0, reason: collision with root package name */
    public final C0533o0 f25721t0;

    /* renamed from: u, reason: collision with root package name */
    public Q f25722u;

    /* renamed from: u0, reason: collision with root package name */
    public final C0525k0 f25723u0;

    /* renamed from: v, reason: collision with root package name */
    public ThermalStreamer f25724v;

    /* renamed from: v0, reason: collision with root package name */
    public final C0533o0 f25725v0;

    /* renamed from: w, reason: collision with root package name */
    public boolean f25726w;

    /* renamed from: w0, reason: collision with root package name */
    public boolean f25727w0;

    /* renamed from: x, reason: collision with root package name */
    public Q f25728x;

    /* renamed from: x0, reason: collision with root package name */
    public final i9.T f25729x0;

    /* renamed from: y, reason: collision with root package name */
    public Q f25730y;

    /* renamed from: y0, reason: collision with root package name */
    public final i9.T f25731y0;

    /* renamed from: z, reason: collision with root package name */
    public boolean f25732z;

    /* renamed from: z0, reason: collision with root package name */
    public final i9.T f25733z0;

    /* renamed from: k, reason: collision with root package name */
    public final t7.p f25702k = AbstractC2157b.m(new A4.d(this, 22));

    /* renamed from: o, reason: collision with root package name */
    public final C0533o0 f25710o = C0508c.u(new Size(0, 0));

    /* renamed from: q, reason: collision with root package name */
    public float f25714q = 2.0f;

    static {
        File externalStoragePublicDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        if (externalStoragePublicDirectory != null) {
            externalStoragePublicDirectory.getPath();
        }
        String str = File.separator;
        f25644N0 = j0.class.getSimpleName();
    }

    public j0(InterfaceC2415c interfaceC2415c, y4.g gVar, t4.N n10, y4.h hVar, y4.f fVar, h4.a aVar, h4.h hVar2, y4.d dVar, y4.e eVar, t4.M m10) {
        this.f25683a = interfaceC2415c;
        this.f25685b = gVar;
        this.f25687c = n10;
        this.f25689d = hVar;
        this.f25691e = fVar;
        this.f25693f = aVar;
        this.f25695g = hVar2;
        this.f25697h = dVar;
        this.i = eVar;
        this.f25700j = m10;
        G0 g0E = f9.F.e();
        m9.f fVar2 = f9.P.f16067a;
        this.f25720t = f9.F.b(C2650t.i(g0E, m9.e.f20045a));
        this.f25657G = C0508c.u(EnumC2424l.LIVE);
        i9.Z zB = i9.a0.b(7, null);
        this.f25672P = zB;
        i9.Z zB2 = i9.a0.b(7, null);
        this.f25673Q = zB2;
        i9.Z zB3 = i9.a0.b(7, null);
        this.f25674R = zB3;
        i9.Z zB4 = i9.a0.b(7, null);
        this.f25675S = zB4;
        Boolean bool = Boolean.FALSE;
        this.f25679W = C0508c.u(bool);
        this.f25680X = C0508c.u(new t4.e0(((C2724s) m10).d(), 0.0f, 0.0f));
        this.f25681Y = C0508c.u(new t4.e0(1.0f, 0.0f, 0.0f));
        this.f25690d0 = "";
        this.f25692e0 = "";
        int[] iArr = new int[0];
        this.f25701j0 = iArr;
        this.f25703k0 = new int[0];
        this.f25705l0 = IntBuffer.wrap(iArr);
        this.f25707m0 = O7.J.N(AppCoreResourceTreeService.class);
        this.f25709n0 = O7.J.N(C2707a.class);
        this.f25711o0 = O7.J.N(C2719m.class);
        this.f25719s0 = -1.0d;
        this.f25721t0 = C0508c.u(null);
        this.f25723u0 = new C0525k0(1.33333f);
        this.f25725v0 = C0508c.u(v() ? new Size(720, 1280) : new Size(720, 960));
        this.f25729x0 = new i9.T(zB);
        this.f25731y0 = new i9.T(zB2);
        this.f25733z0 = new i9.T(zB3);
        this.f25646A0 = new i9.T(zB4);
        this.f25648B0 = new ArrayList();
        this.f25650C0 = new C0527l0(0);
        this.f25652D0 = new C0527l0(-1);
        this.f25654E0 = C0508c.u(new t7.k(Float.valueOf(273.14f), Float.valueOf(293.14f)));
        i9.k0 k0VarC = i9.a0.c(bool);
        this.f25656F0 = k0VarC;
        this.f25658G0 = new i9.U(k0VarC);
        this.f25660H0 = new C0525k0(0.0f);
        this.f25664J0 = C0508c.u(bool);
        this.f25666K0 = C0508c.u(bool);
        this.f25667L0 = o9.d.a();
        this.f25669M0 = new S(this);
    }

    public static boolean A(String str, H7.a aVar) {
        String str2 = f25644N0;
        try {
            Log.d(str2, "Executing GL operation: ".concat(str));
            aVar.invoke();
            Log.d(str2, "GL operation completed successfully: ".concat(str));
            return true;
        } catch (Exception e5) {
            Log.e(str2, "GL operation failed: " + str + " - " + e5.getMessage());
            return false;
        }
    }

    public static final Bitmap b(j0 j0Var, GL10 gl10) {
        j0Var.getClass();
        String str = f25644N0;
        try {
            int width = j0Var.t().getWidth();
            int height = j0Var.t().getHeight();
            j0Var.f25705l0.position(0);
            gl10.glReadPixels(0, 0, width, height, 6408, 5121, j0Var.f25705l0);
            int iGlGetError = gl10.glGetError();
            if (iGlGetError != 0) {
                Log.e(str, "OpenGL error: " + iGlGetError);
                return null;
            }
            for (int i = 0; i < height; i++) {
                int i3 = i * width;
                int i6 = ((height - i) - 1) * width;
                for (int i10 = 0; i10 < width; i10++) {
                    int i11 = j0Var.f25701j0[i3 + i10];
                    j0Var.f25703k0[i6 + i10] = (i11 & (-16711936)) | ((i11 << 16) & 16711680) | ((i11 >> 16) & 255);
                }
            }
            return Bitmap.createBitmap(j0Var.f25703k0, width, height, Bitmap.Config.ARGB_8888);
        } catch (GLException e5) {
            Log.e(str, "GLException: '" + e5.getMessage() + "'");
            return null;
        }
    }

    public static final void c(j0 j0Var) {
        j0Var.getClass();
        String str = f25644N0;
        Log.d(str, "Reinitializing due to stream exception - checking camera connection");
        if (!j0Var.g()) {
            Log.w(str, "Stream exception due to camera disconnection - awaiting reconnection");
            return;
        }
        Q q10 = j0Var.f25728x;
        if (q10 != null) {
            q10.cancel();
        }
        j0Var.f25728x = null;
        Q q11 = j0Var.f25730y;
        if (q11 != null) {
            q11.cancel();
        }
        j0Var.f25730y = null;
        Timer timer = new Timer("initialStreamExceptionTimer");
        Q q12 = new Q(j0Var, 0);
        timer.schedule(q12, 300L, 300L);
        j0Var.f25728x = q12;
    }

    /* JADX WARN: Removed duplicated region for block: B:7:0x0020  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public static final Object d(j0 j0Var, E6.i iVar, JavaImageBuffer javaImageBuffer, AbstractC2886c abstractC2886c) throws FileNotFoundException {
        T t9;
        ContentResolver contentResolver;
        ParcelFileDescriptor parcelFileDescriptorOpenFileDescriptor;
        ContentResolver contentResolver2;
        j0Var.getClass();
        String str = f25644N0;
        if (abstractC2886c instanceof T) {
            t9 = (T) abstractC2886c;
            int i = t9.f25561c;
            if ((i & Integer.MIN_VALUE) != 0) {
                t9.f25561c = i - Integer.MIN_VALUE;
            } else {
                t9 = new T(j0Var, abstractC2886c);
            }
        }
        Object obj = t9.f25559a;
        EnumC2851a enumC2851a = EnumC2851a.COROUTINE_SUSPENDED;
        int i3 = t9.f25561c;
        if (i3 == 0) {
            AbstractC2176a.l(obj);
            if (j0Var.u() != EnumC2424l.RECALL && j0Var.f25694f0 != null) {
                try {
                    if (j0Var.f25708n) {
                        String str2 = j0Var.f25692e0;
                        File file = new File(str2);
                        if (file.exists()) {
                            file.delete();
                        }
                        Camera camera = j0Var.f25706m;
                        if (camera != null) {
                            camera.requestHighResolutionSnapshotToFile(str2);
                        }
                        File file2 = new File(str2);
                        if (file2.exists()) {
                            O1.h hVar = new O1.h(str2);
                            hVar.E("Orientation", "8");
                            hVar.A();
                            if (iVar != null) {
                                Bitmap bitmapDecodeFile = BitmapFactory.decodeFile(str2);
                                kotlin.jvm.internal.l.b(bitmapDecodeFile);
                                Bitmap bitmapCreateScaledBitmap = Bitmap.createScaledBitmap(bitmapDecodeFile, 480, 640, true);
                                Matrix matrix = new Matrix();
                                matrix.postRotate(270.0f);
                                iVar.invoke(Bitmap.createBitmap(bitmapCreateScaledBitmap, 0, 0, bitmapCreateScaledBitmap.getWidth(), bitmapCreateScaledBitmap.getHeight(), matrix, true));
                            }
                            Application application = j0Var.f25686b0;
                            if (application == null || (contentResolver2 = application.getContentResolver()) == null) {
                                parcelFileDescriptorOpenFileDescriptor = null;
                            } else {
                                Uri uri = j0Var.f25694f0;
                                kotlin.jvm.internal.l.b(uri);
                                parcelFileDescriptorOpenFileDescriptor = contentResolver2.openFileDescriptor(uri, "w");
                            }
                            if (parcelFileDescriptorOpenFileDescriptor != null) {
                                try {
                                    FileOutputStream fileOutputStream = new FileOutputStream(parcelFileDescriptorOpenFileDescriptor.getFileDescriptor());
                                    try {
                                        FileInputStream fileInputStream = new FileInputStream(file2);
                                        try {
                                            long jB = ba.g.B(fileInputStream, fileOutputStream);
                                            fileInputStream.close();
                                            fileOutputStream.close();
                                            parcelFileDescriptorOpenFileDescriptor.close();
                                            new Long(jB);
                                        } finally {
                                        }
                                    } finally {
                                    }
                                } catch (Throwable th) {
                                    try {
                                        throw th;
                                    } catch (Throwable th2) {
                                        u0.m(parcelFileDescriptorOpenFileDescriptor, th);
                                        throw th2;
                                    }
                                }
                            } else {
                                z7.f.a(Log.d(str, "saveFlattenedCopy(): Description is null."));
                            }
                        } else {
                            z7.f.a(Log.e(str, "saveFlattenedCopy(): File '" + str2 + "' do not exist."));
                        }
                    } else if (javaImageBuffer != null) {
                        Bitmap bitMap = BitmapAndroid.createBitmap(javaImageBuffer).getBitMap();
                        if (iVar != null) {
                            kotlin.jvm.internal.l.b(bitMap);
                            iVar.invoke(bitMap);
                        }
                        Application application2 = j0Var.f25686b0;
                        if (application2 != null && (contentResolver = application2.getContentResolver()) != null) {
                            Uri uri2 = j0Var.f25694f0;
                            kotlin.jvm.internal.l.b(uri2);
                            OutputStream outputStreamOpenOutputStream = contentResolver.openOutputStream(uri2, "w");
                            if (outputStreamOpenOutputStream != null) {
                                try {
                                    bitMap.compress(Bitmap.CompressFormat.JPEG, 100, outputStreamOpenOutputStream);
                                    outputStreamOpenOutputStream.flush();
                                    outputStreamOpenOutputStream.close();
                                } catch (Throwable th3) {
                                    try {
                                        throw th3;
                                    } catch (Throwable th4) {
                                        u0.m(outputStreamOpenOutputStream, th3);
                                        throw th4;
                                    }
                                }
                            }
                        }
                    }
                } catch (CancellationException e5) {
                    Log.w(str, "saveLargeVisualOnlyImage: Coroutine cancelled during snapshot processing.", e5);
                    throw e5;
                } catch (RuntimeException unused) {
                    m9.f fVar = f9.P.f16067a;
                    g9.c cVar = k9.m.f19034a;
                    U u3 = new U(j0Var, null);
                    t9.f25561c = 1;
                    if (f9.F.L(cVar, u3, t9) == enumC2851a) {
                        return enumC2851a;
                    }
                } catch (Throwable th5) {
                    th5.printStackTrace();
                    z7.f.a(Log.e(str, "saveFlattenedCopy(): Exception saving JPEG: " + th5.getMessage()));
                }
            }
        } else {
            if (i3 != 1) {
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
            AbstractC2176a.l(obj);
        }
        return t7.w.f23807a;
    }

    public static final void e(j0 j0Var, ThermalImage thermalImage) {
        if (j0Var.L) {
            ThermalImageFile thermalImageFile = j0Var.f25663J;
            if (thermalImageFile != null) {
                j0Var.U(thermalImageFile, false);
                j0Var.V(thermalImageFile, false);
            }
            j0Var.k();
            return;
        }
        if (thermalImage != null) {
            synchronized (j0Var) {
                try {
                    if (!j0Var.L) {
                        int i = J.f25536a[j0Var.u().ordinal()];
                        if (i == 1) {
                            ThermalImageFile thermalImageFile2 = j0Var.f25659H;
                            if (thermalImageFile2 != null) {
                                j0Var.V(thermalImageFile2, false);
                            }
                        } else if (i == 2) {
                            ThermalImageFile thermalImageFile3 = j0Var.f25663J;
                            if (thermalImageFile3 != null) {
                                j0Var.V(thermalImageFile3, false);
                            }
                        } else {
                            if (i != 3) {
                                throw new A8.M();
                            }
                            j0Var.V(thermalImage, true);
                        }
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
            if (j0Var.u() == EnumC2424l.FREEZE || j0Var.u() == EnumC2424l.RECALL) {
                j0Var.z(thermalImage);
            }
        }
    }

    public final void B(Size size) {
        this.f25710o.setValue(size);
        C0903t c0903tA = ((C1443b) this.f25693f).a();
        if (c0903tA != null) {
            m9.f fVar = f9.P.f16067a;
            f9.F.B(c0903tA, m9.e.f20045a, null, new K(this, null), 2);
        }
    }

    public final void C(boolean z4) {
        if (this.f25696g0) {
            Log.e(f25644N0, "Cannot change screen mode while recording video");
            return;
        }
        this.f25679W.setValue(Boolean.valueOf(z4));
        if (v()) {
            I(new t4.e0(1.0f, 0.0f, 0.0f));
        }
        this.f25684a0 = true;
    }

    public final synchronized void D(boolean z4) {
        try {
            EnumC2424l enumC2424lU = u();
            EnumC2424l enumC2424l = EnumC2424l.LIVE;
            boolean z10 = enumC2424lU != enumC2424l;
            this.f25657G.setValue(enumC2424l);
            this.f25659H = null;
            ImageColorizer imageColorizer = this.f25661I;
            if (imageColorizer != null) {
                imageColorizer.close();
            }
            ImageColorizer imageColorizer2 = this.f25668M;
            if (imageColorizer2 != null) {
                imageColorizer2.close();
            }
            this.f25661I = null;
            this.f25668M = null;
            this.f25665K = null;
            this.f25663J = null;
            if (!z4) {
                try {
                    if (this.f25704l != null && (!r6.isStreaming())) {
                        if (this.f25708n) {
                            M();
                        } else {
                            K();
                        }
                    }
                    H(true);
                    G(new t4.e0(((C2724s) this.f25700j).d(), 0.0f, 0.0f));
                    if (z10) {
                        C(Boolean.parseBoolean(((C2720n) this.f25697h).c("resource_tree/image_adjust/live_full_screen")));
                        String strC = ((C2720n) this.f25697h).c("resource_tree/image_adjust/adjust_mode");
                        if (strC != null) {
                            ((C2727v) this.f25691e).i(EnumC2427o.valueOf(strC));
                        }
                    }
                    ((C2704D) this.f25685b).f25516o = true;
                } catch (Exception e5) {
                    Log.d(f25644N0, "setLive() exception. " + e5.getMessage());
                }
            }
            k();
        } catch (Throwable th) {
            throw th;
        }
    }

    public final void E(boolean z4) {
        this.f25727w0 = z4;
        if (z4 && !this.f25670N) {
            this.f25670N = true;
        }
        Log.d(f25644N0, "liveStreamReady: " + r() + " (field = " + this.f25727w0 + ")");
    }

    public final void F(boolean z4) {
        if (z4 != this.f25712p) {
            this.f25712p = z4;
            C0903t c0903tA = ((C1443b) this.f25693f).a();
            if (c0903tA != null) {
                m9.f fVar = f9.P.f16067a;
                f9.F.B(c0903tA, m9.e.f20045a, null, new P(this, null), 2);
            }
        }
    }

    public final void G(t4.e0 e0Var) {
        if (v()) {
            Log.d(f25644N0, "Zoom is not allowed in full screen mode.");
            return;
        }
        float f10 = e0Var.f23674a;
        if (f10 < 1.0f) {
            f10 = 1.0f;
        }
        float fFloatValue = ((Number) this.f25702k.getValue()).floatValue();
        if (f10 > fFloatValue) {
            f10 = fFloatValue;
        }
        I(new t4.e0(f10, w().f23675b, w().f23676c));
        this.f25684a0 = true;
        if (u() == EnumC2424l.LIVE) {
            float f11 = w().f23674a;
            ((C2720n) ((C2724s) this.f25700j).f25771a).e("resource_tree/image_adjust/zoom_parameters", String.valueOf(f11));
        }
    }

    public final void H(boolean z4) {
        this.f25684a0 = z4;
        if (z4) {
            return;
        }
        Q q10 = this.f25682Z;
        if (q10 != null) {
            q10.cancel();
        }
        Timer timer = new Timer("zoomChokeFilter");
        Q q11 = new Q(this, 2);
        timer.schedule(q11, 100L);
        this.f25682Z = q11;
    }

    public final synchronized void I(t4.e0 e0Var) {
        this.f25680X.setValue(e0Var);
    }

    public final void J(Application context) {
        kotlin.jvm.internal.l.e(context, "context");
        this.f25708n = true;
        this.f25651D = false;
        String str = f25644N0;
        Log.d(str, "Setting up OpenGL, background state reset: " + this.f25651D);
        if (q() == null) {
            Log.d(str, "Creating new GL surface view with background cleanup configuration");
            this.f25721t0.setValue(new GLSurfaceView(context));
            GLSurfaceView gLSurfaceViewQ = q();
            if (gLSurfaceViewQ != null) {
                gLSurfaceViewQ.setEGLContextClientVersion(3);
                gLSurfaceViewQ.setPreserveEGLContextOnPause(false);
                gLSurfaceViewQ.setRenderer(this.f25669M0);
                gLSurfaceViewQ.setRenderMode(0);
            }
        } else {
            Log.d(str, "Resuming existing GL surface view after background cleanup");
            GLSurfaceView gLSurfaceViewQ2 = q();
            if (gLSurfaceViewQ2 != null) {
                gLSurfaceViewQ2.onResume();
            }
        }
        this.f25714q = context.getResources().getDisplayMetrics().density;
    }

    public final void K() {
        Stream stream = this.f25704l;
        if (stream == null || stream.isStreaming() || !W("emulator stream")) {
            return;
        }
        if (!((C2723q) this.i).a()) {
            Log.w(f25644N0, "Cannot start emulator stream - not in emulator mode");
            return;
        }
        this.f25651D = false;
        Log.d(f25644N0, "Starting emulator stream, background state reset: " + this.f25651D);
        E(false);
        this.f25684a0 = true;
        stream.start(new H(this, 0), new C2323z(9));
    }

    public final void L() {
        f();
        Timer timer = new Timer("metaDataRefreshTimer");
        Q q10 = new Q(this, 4);
        timer.schedule(q10, 333L, 333L);
        this.f25716r = q10;
    }

    public final void M() {
        Stream stream = this.f25704l;
        if (stream == null || stream.isStreaming() || !W("GL stream")) {
            return;
        }
        boolean z4 = this.f25712p;
        String str = f25644N0;
        if (!z4) {
            Log.w(str, "Cannot start GL stream - pipeline not properly setup");
            return;
        }
        if (((C2723q) this.i).a()) {
            Log.w(str, "Cannot start GL stream - in emulator mode");
            return;
        }
        this.f25651D = false;
        Log.d(str, "Starting GL stream, background state reset: " + this.f25651D);
        E(false);
        this.f25684a0 = true;
        this.f25671O = 0;
        C0903t c0903tA = ((C1443b) this.f25693f).a();
        if (c0903tA != null) {
            m9.f fVar = f9.P.f16067a;
            f9.F.B(c0903tA, m9.e.f20045a, null, new e0(stream, this, null), 2);
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0013  */
    /* JADX WARN: Type inference failed for: r8v5, types: [H7.a] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public final Object N(C1032q c1032q, AbstractC2886c abstractC2886c) throws Throwable {
        f0 f0Var;
        InterfaceC2087a interfaceC2087a;
        int i;
        kotlin.jvm.internal.z zVar;
        InterfaceC2087a interfaceC2087a2;
        if (abstractC2886c instanceof f0) {
            f0Var = (f0) abstractC2886c;
            int i3 = f0Var.f25615t;
            if ((i3 & Integer.MIN_VALUE) != 0) {
                f0Var.f25615t = i3 - Integer.MIN_VALUE;
            } else {
                f0Var = new f0(this, abstractC2886c);
            }
        }
        Object objL = f0Var.f25613e;
        EnumC2851a enumC2851a = EnumC2851a.COROUTINE_SUSPENDED;
        int i6 = f0Var.f25615t;
        t7.w wVar = t7.w.f23807a;
        try {
            try {
                if (i6 == 0) {
                    AbstractC2176a.l(objL);
                    kotlin.jvm.internal.z zVar2 = new kotlin.jvm.internal.z();
                    long jCurrentTimeMillis = System.currentTimeMillis();
                    zVar2.f19064a = jCurrentTimeMillis;
                    String str = f25644N0;
                    Log.d(str, "TSDKStreamingProvider.streamStop(): time = " + jCurrentTimeMillis);
                    if (this.f25649C) {
                        Log.d(str, "Stream stop already in progress, skipping");
                        m9.f fVar = f9.P.f16067a;
                        g9.c cVar = k9.m.f19034a;
                        g0 g0Var = new g0(c1032q, null);
                        f0Var.f25609a = null;
                        f0Var.f25610b = null;
                        f0Var.f25615t = 1;
                        if (f9.F.L(cVar, g0Var, f0Var) != enumC2851a) {
                            return wVar;
                        }
                    } else {
                        interfaceC2087a = this.f25667L0;
                        f0Var.f25609a = c1032q;
                        f0Var.f25610b = zVar2;
                        f0Var.f25611c = interfaceC2087a;
                        i = 0;
                        f0Var.f25612d = 0;
                        f0Var.f25615t = 2;
                        if (interfaceC2087a.b(f0Var) != enumC2851a) {
                            zVar = zVar2;
                        }
                    }
                    return enumC2851a;
                }
                if (i6 == 1) {
                    AbstractC2176a.l(objL);
                    return wVar;
                }
                if (i6 != 2) {
                    if (i6 != 3) {
                        throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
                    }
                    interfaceC2087a2 = f0Var.f25611c;
                    try {
                        AbstractC2176a.l(objL);
                        ((Number) objL).intValue();
                        interfaceC2087a2.a(null);
                        return wVar;
                    } catch (Throwable th) {
                        th = th;
                        interfaceC2087a2.a(null);
                        throw th;
                    }
                }
                int i10 = f0Var.f25612d;
                interfaceC2087a = f0Var.f25611c;
                zVar = f0Var.f25610b;
                ?? r82 = f0Var.f25609a;
                AbstractC2176a.l(objL);
                i = i10;
                c1032q = r82;
                m9.f fVar2 = f9.P.f16067a;
                m9.e eVar = m9.e.f20045a;
                i0 i0Var = new i0(zVar, this, c1032q, null);
                f0Var.f25609a = null;
                f0Var.f25610b = null;
                f0Var.f25611c = interfaceC2087a;
                f0Var.f25612d = i;
                f0Var.f25615t = 3;
                objL = f9.F.L(eVar, i0Var, f0Var);
                if (objL != enumC2851a) {
                    interfaceC2087a2 = interfaceC2087a;
                    ((Number) objL).intValue();
                    interfaceC2087a2.a(null);
                    return wVar;
                }
                return enumC2851a;
            } catch (Throwable th2) {
                th = th2;
                interfaceC2087a2 = interfaceC2087a;
                interfaceC2087a2.a(null);
                throw th;
            }
            this.f25649C = true;
            this.f25651D = true;
        } catch (Throwable th3) {
            th = th3;
        }
    }

    public final void O() {
        C0533o0 c0533o0 = this.f25664J0;
        c0533o0.setValue(Boolean.valueOf(!((Boolean) c0533o0.getValue()).booleanValue()));
        Camera camera = this.f25706m;
        if (camera != null) {
            camera.toggleLamp(((Boolean) c0533o0.getValue()).booleanValue());
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public final void P() {
        RemoteControl remoteControl;
        Property<Boolean> propertyLaserOn;
        C0533o0 c0533o0 = this.f25666K0;
        c0533o0.setValue(Boolean.valueOf(!((Boolean) c0533o0.getValue()).booleanValue()));
        Camera camera = this.f25706m;
        if (camera == null || (remoteControl = camera.getRemoteControl()) == null || (propertyLaserOn = remoteControl.laserOn()) == 0) {
            return;
        }
        propertyLaserOn.setSync(c0533o0.getValue());
    }

    public final void Q() {
        if (((Boolean) this.f25664J0.getValue()).booleanValue()) {
            O();
        }
        if (((Boolean) this.f25666K0.getValue()).booleanValue()) {
            P();
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public final synchronized void R(ThermalImage thermalImage) {
        RemoteControl remoteControl;
        TemperatureRange temperatureRange;
        CameraInformation cameraInformation;
        ThermalValue thermalValue;
        CameraInformation cameraInformation2;
        ThermalValue thermalValue2;
        RemoteControl remoteControl2;
        TemperatureRange temperatureRange2;
        Property<Integer> propertySelectedIndex;
        try {
            i();
            Camera camera = this.f25706m;
            if (camera != null && (remoteControl2 = camera.getRemoteControl()) != null && (temperatureRange2 = remoteControl2.getTemperatureRange()) != null && (propertySelectedIndex = temperatureRange2.selectedIndex()) != null) {
                propertySelectedIndex.unsubscribe();
            }
            if (u() == EnumC2424l.LIVE) {
                try {
                    Camera camera2 = this.f25706m;
                    if (camera2 != null && (remoteControl = camera2.getRemoteControl()) != null && (temperatureRange = remoteControl.getTemperatureRange()) != null) {
                        ArrayList<Pair<ThermalValue, ThermalValue>> sync = temperatureRange.ranges().getSync();
                        kotlin.jvm.internal.l.b(sync);
                        Iterator<T> it = sync.iterator();
                        while (it.hasNext()) {
                            Pair pair = (Pair) it.next();
                            ArrayList arrayList = this.f25648B0;
                            F first = pair.first;
                            kotlin.jvm.internal.l.d(first, "first");
                            Float fValueOf = Float.valueOf((float) AbstractC2157b.t((ThermalValue) first));
                            S second = pair.second;
                            kotlin.jvm.internal.l.d(second, "second");
                            arrayList.add(new t7.k(fValueOf, Float.valueOf((float) AbstractC2157b.t((ThermalValue) second))));
                        }
                        this.f25650C0.l(this.f25648B0.size());
                        Integer sync2 = temperatureRange.selectedIndex().getSync();
                        kotlin.jvm.internal.l.d(sync2, "getSync(...)");
                        S(sync2.intValue());
                        temperatureRange.selectedIndex().subscribe(new H(this, 4));
                    }
                } catch (Exception e5) {
                    Log.e(f25644N0, "Error: Failed to read live temperature ranges. Exception = " + e5);
                }
            } else if (thermalImage == null || u() != EnumC2424l.RECALL) {
                Log.e(f25644N0, "TSDKStreamingProvider.updateCheckTemperatureRanges: invalid temperature range condition.");
            } else {
                ThermalImageFile thermalImageFile = this.f25663J;
                float fT = (thermalImageFile == null || (cameraInformation2 = thermalImageFile.getCameraInformation()) == null || (thermalValue2 = cameraInformation2.rangeMax) == null) ? 0.0f : (float) AbstractC2157b.t(thermalValue2);
                ThermalImageFile thermalImageFile2 = this.f25663J;
                float fT2 = (thermalImageFile2 == null || (cameraInformation = thermalImageFile2.getCameraInformation()) == null || (thermalValue = cameraInformation.rangeMin) == null) ? 0.0f : (float) AbstractC2157b.t(thermalValue);
                if (fT <= 0.0f || fT2 <= 0.0f) {
                    Log.e(f25644N0, "TSDKStreamingProvider.updateCheckTemperatureRanges: invalid recall temperature range.");
                } else {
                    this.f25648B0.add(new t7.k(Float.valueOf(fT2), Float.valueOf(fT)));
                    this.f25650C0.l(this.f25648B0.size());
                    S(0);
                }
            }
        } catch (Throwable th) {
            throw th;
        }
    }

    public final void S(int i) {
        ArrayList arrayList = this.f25648B0;
        C0527l0 c0527l0 = this.f25652D0;
        if (i < 0 || i >= arrayList.size()) {
            Log.e(f25644N0, AbstractC0109q.n("TSDKStreamingProvider.updateCurrentTemperatureRange: Invalid temperature range index ", c0527l0.k(), arrayList.size(), " in list size = "));
            return;
        }
        c0527l0.l(i);
        t7.k kVar = (t7.k) arrayList.get(c0527l0.k());
        kotlin.jvm.internal.l.e(kVar, "<set-?>");
        this.f25654E0.setValue(kVar);
        Q q10 = this.f25722u;
        if (q10 != null) {
            q10.cancel();
        }
        this.f25722u = null;
        C0903t c0903tA = ((C1443b) this.f25693f).a();
        if (c0903tA != null) {
            m9.f fVar = f9.P.f16067a;
            f9.F.B(c0903tA, m9.e.f20045a, null, new O(this, null), 2);
        }
    }

    public final boolean T(Camera camera, ThermalImage thermalImage) throws NumberFormatException {
        Camera camera2;
        boolean z4;
        if (u() == EnumC2424l.RECALL && thermalImage != null) {
            B(new Size(thermalImage.getWidth(), thermalImage.getHeight()));
        }
        boolean z10 = true;
        if (m().getWidth() != 0 && m().getHeight() != 0) {
            boolean z11 = false;
            int width = camera != null ? m().getWidth() : thermalImage != null ? thermalImage.getWidth() : 0;
            int height = camera != null ? m().getHeight() : thermalImage != null ? thermalImage.getHeight() : 0;
            float f10 = ((C2723q) this.i).a() ? width / height : height / width;
            C0525k0 c0525k0 = this.f25723u0;
            c0525k0.l(f10);
            float height2 = v() ? t().getHeight() / c0525k0.k() : t().getWidth();
            float height3 = v() ? t().getHeight() : t().getHeight();
            float f11 = 2;
            float width2 = (t().getWidth() - height2) / f11;
            float height4 = (t().getHeight() - height3) / f11;
            String str = f25644N0;
            if (camera != null) {
                int i = (int) height3;
                camera2 = camera;
                try {
                    camera2.glSetViewport((int) width2, (int) height4, (int) height2, i, 0.0f);
                } catch (Exception e5) {
                    Log.e(str, "updateDimensions(): glSetViewport exception = '" + e5.getMessage() + "'");
                    z4 = false;
                }
            } else {
                camera2 = camera;
            }
            z4 = true;
            this.f25681Y.setValue(new t4.e0(1.0f, width2, height4));
            I(new t4.e0(w().f23674a, (t().getWidth() - (t().getWidth() * w().f23674a)) / f11, (t().getHeight() - (t().getHeight() * w().f23674a)) / f11));
            try {
                DisplaySettings displaySettings = new DisplaySettings(w().f23674a, 0, 0, FlipType.NONE);
                int i3 = J.f25536a[u().ordinal()];
                if (i3 == 1) {
                    ThermalImageFile thermalImageFile = this.f25659H;
                    if (thermalImageFile != null) {
                        thermalImageFile.setDisplaySettings(displaySettings);
                    }
                } else if (i3 == 2) {
                    ThermalImageFile thermalImageFile2 = this.f25663J;
                    if (thermalImageFile2 != null) {
                        thermalImageFile2.setDisplaySettings(displaySettings);
                    }
                } else {
                    if (i3 != 3) {
                        throw new A8.M();
                    }
                    if (camera2 != null) {
                        camera2.glWithThermalImage(new C1451b(displaySettings, 12));
                    }
                }
                z11 = z4;
            } catch (Exception e8) {
                Log.e(str, "updateDimensions(): setDisplaySettings exception = '" + e8.getMessage() + "'");
            }
            t4.e0 zoom = w();
            boolean zV = v();
            u();
            EnumC2424l enumC2424l = EnumC2424l.LIVE;
            C2704D c2704d = (C2704D) this.f25685b;
            kotlin.jvm.internal.l.e(zoom, "zoom");
            if (!kotlin.jvm.internal.l.a(c2704d.f25507e, zoom)) {
                c2704d.f25507e = zoom;
            }
            if (c2704d.f25509g != zV) {
                c2704d.f25509g = zV;
            }
            c2704d.f25508f = true;
            this.f25726w = true;
            z10 = z11;
        }
        s();
        return z10;
    }

    public final synchronized void U(ThermalImage thermalImage, boolean z4) {
        ImageColorizer imageColorizer;
        ImageBuffer image;
        Bitmap bitMap;
        InterfaceC1968a interfaceC1968a;
        try {
            if (!r() && z4) {
                E(true);
            }
            if (this.f25684a0) {
                if (T(null, thermalImage)) {
                    H(false);
                } else {
                    Log.d(f25644N0, "updateDimensions() failed");
                }
            }
            int i = J.f25536a[u().ordinal()];
            if (i == 1) {
                imageColorizer = this.f25661I;
            } else if (i == 2) {
                imageColorizer = this.f25668M;
            } else {
                if (i != 3) {
                    throw new A8.M();
                }
                imageColorizer = new ImageColorizer(thermalImage);
            }
            if (imageColorizer != null) {
                imageColorizer.setAutoScale(y());
                imageColorizer.update();
                com.flir.earhart.common.viewModel.b bVar = this.f25676T;
                if (bVar != null && ((!this.f25708n || !z4 || this.f25696g0) && (image = imageColorizer.getImage()) != null && (bitMap = BitmapAndroid.createBitmap(image).getBitMap()) != null)) {
                    bVar.invoke(bitMap);
                    if (!this.f25708n && this.f25696g0 && (interfaceC1968a = this.f25699i0) != null) {
                        ((m4.d) interfaceC1968a).c(bitMap, this.f25662I0);
                    }
                }
            }
            if (u() == EnumC2424l.LIVE && imageColorizer != null) {
                imageColorizer.close();
            }
        } catch (Throwable th) {
            throw th;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:109:0x01f1 A[Catch: all -> 0x0019, TryCatch #0 {all -> 0x0019, blocks: (B:4:0x0007, B:6:0x0016, B:10:0x001e, B:11:0x0025, B:13:0x0030, B:17:0x003e, B:18:0x0045, B:20:0x004e, B:23:0x0059, B:24:0x0060, B:26:0x006b, B:32:0x0078, B:34:0x0080, B:36:0x0088, B:37:0x008f, B:38:0x0096, B:40:0x00a0, B:41:0x00a8, B:44:0x00b3, B:49:0x00c8, B:52:0x00ce, B:65:0x00f3, B:72:0x0101, B:73:0x010a, B:75:0x0114, B:77:0x011a, B:83:0x012b, B:88:0x013f, B:90:0x0143, B:94:0x014a, B:147:0x0289, B:150:0x0293, B:152:0x0298, B:154:0x02a3, B:96:0x0155, B:107:0x01ed, B:109:0x01f1, B:110:0x020c, B:115:0x0215, B:117:0x0219, B:119:0x021d, B:121:0x0221, B:123:0x0225, B:125:0x022c, B:131:0x024f, B:128:0x0232, B:132:0x0258, B:142:0x0279, B:145:0x027f, B:146:0x0282, B:134:0x025f, B:136:0x0263, B:139:0x026b, B:141:0x0271, B:98:0x018a, B:100:0x018e, B:102:0x0192, B:104:0x019a, B:106:0x01c7, B:59:0x00e0, B:61:0x00e7, B:62:0x00ec, B:63:0x00ed, B:64:0x00f0), top: B:159:0x0007, inners: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:133:0x025d  */
    /* JADX WARN: Removed duplicated region for block: B:152:0x0298 A[Catch: all -> 0x0019, TryCatch #0 {all -> 0x0019, blocks: (B:4:0x0007, B:6:0x0016, B:10:0x001e, B:11:0x0025, B:13:0x0030, B:17:0x003e, B:18:0x0045, B:20:0x004e, B:23:0x0059, B:24:0x0060, B:26:0x006b, B:32:0x0078, B:34:0x0080, B:36:0x0088, B:37:0x008f, B:38:0x0096, B:40:0x00a0, B:41:0x00a8, B:44:0x00b3, B:49:0x00c8, B:52:0x00ce, B:65:0x00f3, B:72:0x0101, B:73:0x010a, B:75:0x0114, B:77:0x011a, B:83:0x012b, B:88:0x013f, B:90:0x0143, B:94:0x014a, B:147:0x0289, B:150:0x0293, B:152:0x0298, B:154:0x02a3, B:96:0x0155, B:107:0x01ed, B:109:0x01f1, B:110:0x020c, B:115:0x0215, B:117:0x0219, B:119:0x021d, B:121:0x0221, B:123:0x0225, B:125:0x022c, B:131:0x024f, B:128:0x0232, B:132:0x0258, B:142:0x0279, B:145:0x027f, B:146:0x0282, B:134:0x025f, B:136:0x0263, B:139:0x026b, B:141:0x0271, B:98:0x018a, B:100:0x018e, B:102:0x0192, B:104:0x019a, B:106:0x01c7, B:59:0x00e0, B:61:0x00e7, B:62:0x00ec, B:63:0x00ed, B:64:0x00f0), top: B:159:0x0007, inners: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:154:0x02a3 A[Catch: all -> 0x0019, TRY_LEAVE, TryCatch #0 {all -> 0x0019, blocks: (B:4:0x0007, B:6:0x0016, B:10:0x001e, B:11:0x0025, B:13:0x0030, B:17:0x003e, B:18:0x0045, B:20:0x004e, B:23:0x0059, B:24:0x0060, B:26:0x006b, B:32:0x0078, B:34:0x0080, B:36:0x0088, B:37:0x008f, B:38:0x0096, B:40:0x00a0, B:41:0x00a8, B:44:0x00b3, B:49:0x00c8, B:52:0x00ce, B:65:0x00f3, B:72:0x0101, B:73:0x010a, B:75:0x0114, B:77:0x011a, B:83:0x012b, B:88:0x013f, B:90:0x0143, B:94:0x014a, B:147:0x0289, B:150:0x0293, B:152:0x0298, B:154:0x02a3, B:96:0x0155, B:107:0x01ed, B:109:0x01f1, B:110:0x020c, B:115:0x0215, B:117:0x0219, B:119:0x021d, B:121:0x0221, B:123:0x0225, B:125:0x022c, B:131:0x024f, B:128:0x0232, B:132:0x0258, B:142:0x0279, B:145:0x027f, B:146:0x0282, B:134:0x025f, B:136:0x0263, B:139:0x026b, B:141:0x0271, B:98:0x018a, B:100:0x018e, B:102:0x0192, B:104:0x019a, B:106:0x01c7, B:59:0x00e0, B:61:0x00e7, B:62:0x00ec, B:63:0x00ed, B:64:0x00f0), top: B:159:0x0007, inners: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:70:0x00fe  */
    /* JADX WARN: Removed duplicated region for block: B:72:0x0101 A[Catch: all -> 0x0019, TryCatch #0 {all -> 0x0019, blocks: (B:4:0x0007, B:6:0x0016, B:10:0x001e, B:11:0x0025, B:13:0x0030, B:17:0x003e, B:18:0x0045, B:20:0x004e, B:23:0x0059, B:24:0x0060, B:26:0x006b, B:32:0x0078, B:34:0x0080, B:36:0x0088, B:37:0x008f, B:38:0x0096, B:40:0x00a0, B:41:0x00a8, B:44:0x00b3, B:49:0x00c8, B:52:0x00ce, B:65:0x00f3, B:72:0x0101, B:73:0x010a, B:75:0x0114, B:77:0x011a, B:83:0x012b, B:88:0x013f, B:90:0x0143, B:94:0x014a, B:147:0x0289, B:150:0x0293, B:152:0x0298, B:154:0x02a3, B:96:0x0155, B:107:0x01ed, B:109:0x01f1, B:110:0x020c, B:115:0x0215, B:117:0x0219, B:119:0x021d, B:121:0x0221, B:123:0x0225, B:125:0x022c, B:131:0x024f, B:128:0x0232, B:132:0x0258, B:142:0x0279, B:145:0x027f, B:146:0x0282, B:134:0x025f, B:136:0x0263, B:139:0x026b, B:141:0x0271, B:98:0x018a, B:100:0x018e, B:102:0x0192, B:104:0x019a, B:106:0x01c7, B:59:0x00e0, B:61:0x00e7, B:62:0x00ec, B:63:0x00ed, B:64:0x00f0), top: B:159:0x0007, inners: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:75:0x0114 A[Catch: all -> 0x0019, TryCatch #0 {all -> 0x0019, blocks: (B:4:0x0007, B:6:0x0016, B:10:0x001e, B:11:0x0025, B:13:0x0030, B:17:0x003e, B:18:0x0045, B:20:0x004e, B:23:0x0059, B:24:0x0060, B:26:0x006b, B:32:0x0078, B:34:0x0080, B:36:0x0088, B:37:0x008f, B:38:0x0096, B:40:0x00a0, B:41:0x00a8, B:44:0x00b3, B:49:0x00c8, B:52:0x00ce, B:65:0x00f3, B:72:0x0101, B:73:0x010a, B:75:0x0114, B:77:0x011a, B:83:0x012b, B:88:0x013f, B:90:0x0143, B:94:0x014a, B:147:0x0289, B:150:0x0293, B:152:0x0298, B:154:0x02a3, B:96:0x0155, B:107:0x01ed, B:109:0x01f1, B:110:0x020c, B:115:0x0215, B:117:0x0219, B:119:0x021d, B:121:0x0221, B:123:0x0225, B:125:0x022c, B:131:0x024f, B:128:0x0232, B:132:0x0258, B:142:0x0279, B:145:0x027f, B:146:0x0282, B:134:0x025f, B:136:0x0263, B:139:0x026b, B:141:0x0271, B:98:0x018a, B:100:0x018e, B:102:0x0192, B:104:0x019a, B:106:0x01c7, B:59:0x00e0, B:61:0x00e7, B:62:0x00ec, B:63:0x00ed, B:64:0x00f0), top: B:159:0x0007, inners: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:84:0x0137  */
    /* JADX WARN: Removed duplicated region for block: B:95:0x0153  */
    /* JADX WARN: Removed duplicated region for block: B:96:0x0155 A[Catch: all -> 0x0019, TryCatch #0 {all -> 0x0019, blocks: (B:4:0x0007, B:6:0x0016, B:10:0x001e, B:11:0x0025, B:13:0x0030, B:17:0x003e, B:18:0x0045, B:20:0x004e, B:23:0x0059, B:24:0x0060, B:26:0x006b, B:32:0x0078, B:34:0x0080, B:36:0x0088, B:37:0x008f, B:38:0x0096, B:40:0x00a0, B:41:0x00a8, B:44:0x00b3, B:49:0x00c8, B:52:0x00ce, B:65:0x00f3, B:72:0x0101, B:73:0x010a, B:75:0x0114, B:77:0x011a, B:83:0x012b, B:88:0x013f, B:90:0x0143, B:94:0x014a, B:147:0x0289, B:150:0x0293, B:152:0x0298, B:154:0x02a3, B:96:0x0155, B:107:0x01ed, B:109:0x01f1, B:110:0x020c, B:115:0x0215, B:117:0x0219, B:119:0x021d, B:121:0x0221, B:123:0x0225, B:125:0x022c, B:131:0x024f, B:128:0x0232, B:132:0x0258, B:142:0x0279, B:145:0x027f, B:146:0x0282, B:134:0x025f, B:136:0x0263, B:139:0x026b, B:141:0x0271, B:98:0x018a, B:100:0x018e, B:102:0x0192, B:104:0x019a, B:106:0x01c7, B:59:0x00e0, B:61:0x00e7, B:62:0x00ec, B:63:0x00ed, B:64:0x00f0), top: B:159:0x0007, inners: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:97:0x0188  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public final synchronized void V(ThermalImage thermalImage, boolean z4) {
        boolean z10;
        boolean z11;
        ImageColorizer imageColorizer;
        ImageColorizer imageColorizer2;
        boolean z12;
        Camera camera;
        Q3.a aVar;
        ImageBuffer scaleImage;
        com.flir.earhart.common.viewModel.b bVar;
        y4.f fVar;
        Camera camera2;
        boolean z13;
        try {
            boolean z14 = this.L;
            boolean z15 = this.f25726w;
            if (((C2727v) this.f25691e).f25779c.f25765b) {
                this.f25713p0 = 0;
            }
            if (z14) {
                ((G) this.f25689d).a(thermalImage);
            }
            if (((G) this.f25689d).c()) {
                ((G) this.f25689d).b(thermalImage);
                z10 = true;
            } else {
                z10 = false;
            }
            boolean z16 = z10;
            if (z14) {
                ((C2727v) this.f25691e).a(thermalImage);
            }
            y4.f fVar2 = this.f25691e;
            if (((C2727v) fVar2).f25786k) {
                ((C2727v) fVar2).d(thermalImage);
                z11 = false;
                z10 = true;
            } else {
                z11 = true;
            }
            if (z14) {
                ((C2727v) this.f25691e).b(thermalImage);
            }
            y4.f fVar3 = this.f25691e;
            if (((C2727v) fVar3).i.f25741h) {
                if ((this.f25671O > 30) || !z4) {
                    ((C2727v) fVar3).e(thermalImage);
                    z10 = true;
                }
            }
            if (z14) {
                if (!kotlin.jvm.internal.l.a(thermalImage, this.f25663J)) {
                    Log.e(f25644N0, "updateThermalMetaData: recalling with thermalImage that is not recallFile.");
                }
                ((C2704D) this.f25685b).a(thermalImage);
            }
            if (((C2704D) this.f25685b).s()) {
                ((C2704D) this.f25685b).d(thermalImage);
                z10 = true;
            }
            if (((C2727v) this.f25691e).h()) {
                z10 = true;
            }
            int i = J.f25536a[u().ordinal()];
            Bitmap bitMap = null;
            if (i == 1) {
                imageColorizer = this.f25661I;
            } else if (i == 2) {
                imageColorizer = this.f25668M;
            } else {
                if (i != 3) {
                    throw new A8.M();
                }
                if (this.f25708n && z4) {
                    if (!(((C2727v) this.f25691e).f25780d != EnumC2725t.OneTouchIdle)) {
                        imageColorizer2 = null;
                        boolean z17 = !y() || z14;
                        if (imageColorizer2 != null) {
                            imageColorizer2.setRenderScale(true);
                            imageColorizer2.setAutoScale(z17);
                            imageColorizer2.update();
                        }
                        if (((C2727v) this.f25691e).h()) {
                            z12 = z17;
                        } else {
                            y4.f fVar4 = this.f25691e;
                            Camera camera3 = this.f25708n ? this.f25706m : null;
                            if (!z4 || z14) {
                                fVar = fVar4;
                                camera2 = camera3;
                                z13 = false;
                            } else {
                                fVar = fVar4;
                                camera2 = camera3;
                                z13 = true;
                            }
                            C2727v c2727v = (C2727v) fVar;
                            z12 = z17;
                            c2727v.c(thermalImage, camera2, z13, z14, imageColorizer2);
                        }
                        if (!z4 || z10 || z14 || (this.f25708n && this.f25696g0)) {
                            if (!z14) {
                                y4.f fVar5 = this.f25691e;
                                ThermalValue rangeMax = thermalImage.getScale().getRangeMax();
                                kotlin.jvm.internal.l.d(rangeMax, "getRangeMax(...)");
                                float fT = (float) AbstractC2157b.t(rangeMax);
                                ThermalValue rangeMin = thermalImage.getScale().getRangeMin();
                                kotlin.jvm.internal.l.d(rangeMin, "getRangeMin(...)");
                                float fT2 = (float) AbstractC2157b.t(rangeMin);
                                C2722p c2722p = ((C2727v) fVar5).f25779c;
                                c2722p.f25766c = fT;
                                c2722p.f25768e = fT2;
                                c2722p.f25767d = true;
                                c2722p.f25769f = true;
                            } else if (z12 && (camera = this.f25706m) != null) {
                                if (this.f25708n && u() == EnumC2424l.LIVE) {
                                    Pair<ThermalValue, ThermalValue> pairGlGetScaleRange = camera.glGetScaleRange();
                                    y4.f fVar6 = this.f25691e;
                                    ThermalValue second = pairGlGetScaleRange.second;
                                    kotlin.jvm.internal.l.d(second, "second");
                                    float fT3 = (float) AbstractC2157b.t(second);
                                    ThermalValue first = pairGlGetScaleRange.first;
                                    kotlin.jvm.internal.l.d(first, "first");
                                    float fT4 = (float) AbstractC2157b.t(first);
                                    C2722p c2722p2 = ((C2727v) fVar6).f25779c;
                                    c2722p2.f25766c = fT3;
                                    c2722p2.f25768e = fT4;
                                } else if (imageColorizer2 != null) {
                                    y4.f fVar7 = this.f25691e;
                                    ThermalValue scaleRangeMax = imageColorizer2.getScaleRangeMax();
                                    kotlin.jvm.internal.l.d(scaleRangeMax, "getScaleRangeMax(...)");
                                    float fT5 = (float) AbstractC2157b.t(scaleRangeMax);
                                    ThermalValue scaleRangeMin = imageColorizer2.getScaleRangeMin();
                                    kotlin.jvm.internal.l.d(scaleRangeMin, "getScaleRangeMin(...)");
                                    float fT6 = (float) AbstractC2157b.t(scaleRangeMin);
                                    C2722p c2722p3 = ((C2727v) fVar7).f25779c;
                                    c2722p3.f25766c = fT5;
                                    c2722p3.f25768e = fT6;
                                }
                            }
                            aVar = this.f25677U;
                            if (aVar != null) {
                                aVar.invoke(Float.valueOf(((C2727v) this.f25691e).f25779c.f25766c), Float.valueOf(((C2727v) this.f25691e).f25779c.f25768e));
                            }
                            if (this.f25708n || !z4) {
                                if (imageColorizer2 != null && this.f25678V != null && ((imageColorizer2.isAutoScale() || z16) && (scaleImage = imageColorizer2.getScaleImage()) != null)) {
                                    bitMap = BitmapAndroid.createBitmap(scaleImage).getBitMap();
                                }
                            } else if (z11 && !this.f25684a0 && this.f25682Z == null) {
                                int i3 = this.f25713p0;
                                if (i3 > 3) {
                                    Camera camera4 = this.f25706m;
                                    if (camera4 != null) {
                                        try {
                                        } catch (Exception e5) {
                                            Log.e(f25644N0, "updateThermalMetaData(): '" + e5.getMessage() + "'");
                                        }
                                        JavaImageBuffer javaImageBufferGlRenderScale = camera4.glIsGlContextReady() ? camera4.glRenderScale() : null;
                                        if (javaImageBufferGlRenderScale != null) {
                                            bitMap = BitmapAndroid.createBitmap(javaImageBufferGlRenderScale).getBitMap();
                                        }
                                    }
                                } else {
                                    this.f25713p0 = i3 + 1;
                                }
                            }
                            bVar = this.f25678V;
                            if (bVar != null && bitMap != null) {
                                bVar.invoke(bitMap);
                            }
                            ((C2704D) this.f25685b).b(thermalImage);
                        } else if (z15) {
                            ((C2704D) this.f25685b).b(thermalImage);
                        }
                        if (u() == EnumC2424l.LIVE && imageColorizer2 != null) {
                            imageColorizer2.close();
                        }
                        if (z14) {
                            this.L = false;
                            ((C2730y) this.f25687c).f25797e = false;
                        }
                        if (z15) {
                            this.f25726w = false;
                        }
                    }
                }
                imageColorizer = new ImageColorizer(thermalImage);
            }
            imageColorizer2 = imageColorizer;
            if (y()) {
            }
            if (imageColorizer2 != null) {
            }
            if (((C2727v) this.f25691e).h()) {
            }
            if (z4) {
                if (!z14) {
                }
                aVar = this.f25677U;
                if (aVar != null) {
                }
                if (this.f25708n) {
                    if (imageColorizer2 != null) {
                        bitMap = BitmapAndroid.createBitmap(scaleImage).getBitMap();
                    }
                    bVar = this.f25678V;
                    if (bVar != null) {
                        bVar.invoke(bitMap);
                    }
                    ((C2704D) this.f25685b).b(thermalImage);
                }
            }
            if (u() == EnumC2424l.LIVE) {
                imageColorizer2.close();
            }
            if (z14) {
            }
            if (z15) {
            }
        } catch (Throwable th) {
            throw th;
        }
    }

    public final boolean W(String str) {
        if (!g()) {
            Log.w(f25644N0, "Cannot start " + str + " - camera disconnected");
            return false;
        }
        if (this.f25651D) {
            Log.w(f25644N0, "Cannot start " + str + " - app is in background");
            return false;
        }
        if (this.f25704l != null) {
            return true;
        }
        Log.w(f25644N0, "Cannot start " + str + " - currentStream is null");
        return false;
    }

    public final void f() {
        Q q10 = this.f25716r;
        if (q10 != null) {
            q10.cancel();
        }
        E0 e02 = this.f25718s;
        if (e02 != null && e02.isActive()) {
            E0 e03 = this.f25718s;
            if (e03 != null) {
                e03.cancel(null);
            }
            this.f25718s = null;
        }
        this.f25716r = null;
    }

    public final boolean g() {
        long jCurrentTimeMillis = System.currentTimeMillis();
        if (jCurrentTimeMillis - this.f25655F < 1000) {
            return !this.f25653E;
        }
        this.f25655F = jCurrentTimeMillis;
        boolean z4 = this.f25653E;
        Camera camera = this.f25706m;
        this.f25653E = !(camera != null && camera.isConnected());
        if (z4 && !this.f25653E) {
            Log.d(f25644N0, "Camera reconnected - resetting state for stream restart");
            this.f25651D = false;
            this.f25649C = false;
        }
        if (!z4 && this.f25653E) {
            Log.w(f25644N0, "Camera disconnected - marking for recovery");
            ((C2720n) this.f25697h).e("resource_tree/last_stream_disconnect", String.valueOf(jCurrentTimeMillis));
        }
        return !this.f25653E;
    }

    public final void h() {
        String str = f25644N0;
        Log.d(str, "TSDKStreamingProvider cleanup started");
        try {
            Log.d(str, "Canceling metadata refresh timer");
            f();
            Log.d(str, "Canceling exception timers");
            Q q10 = this.f25728x;
            if (q10 != null) {
                q10.cancel();
            }
            this.f25728x = null;
            Q q11 = this.f25730y;
            if (q11 != null) {
                q11.cancel();
            }
            this.f25730y = null;
            Log.d(str, "Canceling filter timers");
            Q q12 = this.f25682Z;
            if (q12 != null) {
                q12.cancel();
            }
            this.f25682Z = null;
            Q q13 = this.f25722u;
            if (q13 != null) {
                q13.cancel();
            }
            this.f25722u = null;
            Q q14 = this.f25715q0;
            if (q14 != null) {
                q14.cancel();
            }
            this.f25715q0 = null;
            Log.d(str, "Canceling camera connection monitor timer");
            Q q15 = this.f25717r0;
            if (q15 != null) {
                q15.cancel();
            }
            this.f25717r0 = null;
        } catch (Exception e5) {
            AbstractC0109q.A("Error during timer cleanup: ", e5.getMessage(), f25644N0);
        }
        GLSurfaceView gLSurfaceViewQ = q();
        if (gLSurfaceViewQ != null) {
            Log.d(f25644N0, "Performing GL surface cleanup");
            try {
                gLSurfaceViewQ.onPause();
                gLSurfaceViewQ.queueEvent(new RunnableC0951E(2));
            } catch (Exception e8) {
                Log.w(f25644N0, "GL surface cleanup failed: " + e8.getMessage());
            }
            this.f25721t0.setValue(null);
            Log.d(f25644N0, "GL surface view set to null for complete background cleanup");
        }
        try {
            String str2 = f25644N0;
            Log.d(str2, "Resetting provider state variables");
            this.f25724v = null;
            this.f25663J = null;
            this.f25659H = null;
            ((C2730y) this.f25687c).f25797e = false;
            F(false);
            B(new Size(0, 0));
            this.f25723u0.l(1.33333f);
            this.f25725v0.setValue(v() ? new Size(720, 1280) : new Size(720, 960));
            E(false);
            this.f25706m = null;
            this.f25704l = null;
            this.f25653E = false;
            this.f25655F = 0L;
            Log.d(str2, "Provider state reset completed successfully");
        } catch (Exception e10) {
            AbstractC0109q.A("Error during state reset: ", e10.getMessage(), f25644N0);
        }
        try {
            System.gc();
            Log.d(f25644N0, "Garbage collection completed");
        } catch (Exception e11) {
            Log.w(f25644N0, "Garbage collection failed: " + e11.getMessage());
        }
        this.f25713p0 = 0;
        Log.d(f25644N0, "TSDKStreamingProvider cleanup completed successfully");
        Q();
    }

    public final void i() {
        this.f25648B0.clear();
        this.f25650C0.l(0);
        this.f25652D0.l(-1);
        this.f25654E0.setValue(new t7.k(Float.valueOf(273.14f), Float.valueOf(293.14f)));
        Q q10 = this.f25722u;
        if (q10 != null) {
            q10.cancel();
        }
        this.f25722u = null;
    }

    public final void j(ThermalImage thermalImage, Bitmap bitmap, boolean z4, E6.i iVar, com.flir.earhart.common.provider.a aVar) {
        ThermalImage thermalImage2;
        ImageColorizer imageColorizer;
        ThermalImage thermalImage3;
        float f10;
        Fusion fusion;
        Fusion fusion2;
        Fusion fusion3 = thermalImage.getFusion();
        FusionMode currentFusionMode = fusion3 != null ? fusion3.getCurrentFusionMode() : null;
        FusionMode fusionMode = FusionMode.VISUAL_ONLY;
        h4.a aVar2 = this.f25693f;
        String str = f25644N0;
        if (currentFusionMode == fusionMode) {
            if (u() == EnumC2424l.RECALL) {
                Log.e(str, "doSaveVisualImage: Cannot save large visual image in recall.");
                return;
            }
            JavaImageBuffer photo = (this.f25708n || (fusion2 = thermalImage.getFusion()) == null) ? null : fusion2.getPhoto();
            C0903t c0903tA = ((C1443b) aVar2).a();
            if (c0903tA != null) {
                m9.f fVar = f9.P.f16067a;
                f9.F.B(c0903tA, m9.e.f20045a, null, new M(this, iVar, photo, aVar, null), 2);
                return;
            }
            return;
        }
        u();
        EnumC2424l enumC2424l = EnumC2424l.LIVE;
        int i = J.f25536a[u().ordinal()];
        if (i == 1) {
            thermalImage2 = thermalImage;
            imageColorizer = this.f25661I;
            thermalImage3 = this.f25659H;
        } else if (i == 2) {
            thermalImage2 = thermalImage;
            imageColorizer = this.f25668M;
            thermalImage3 = this.f25663J;
        } else {
            if (i != 3) {
                throw new A8.M();
            }
            thermalImage2 = thermalImage;
            imageColorizer = new ImageColorizer(thermalImage2);
            thermalImage3 = thermalImage2;
        }
        if (imageColorizer == null || thermalImage3 == null) {
            Log.d(str, "TSDKStreamingProvider.doSaveImage: Cannot save image as ImageColorizer or ThermalImage is null");
            return;
        }
        imageColorizer.setAutoScale(y());
        imageColorizer.update();
        Bitmap bitMap = BitmapAndroid.createBitmap(imageColorizer.getImage()).getBitMap();
        kotlin.jvm.internal.l.d(bitMap, "getBitMap(...)");
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float f11 = width;
        float f12 = height;
        float fMax = Float.max(f11 / bitMap.getWidth(), f12 / bitMap.getHeight());
        if (fMax == 1.0f) {
            f10 = 0.0f;
        } else {
            float width2 = bitMap.getWidth() * fMax;
            f10 = 0.0f;
            float height2 = bitMap.getHeight() * fMax;
            bitMap = Bitmap.createScaledBitmap(bitMap, J7.a.K(width2), J7.a.K(height2), true);
            float fFloatValue = (width2 > f11 ? Float.valueOf(width2 - f11) : 0).floatValue();
            float fFloatValue2 = (height2 > f12 ? Float.valueOf(height2 - f12) : 0).floatValue();
            if (fFloatValue > 0.0f || fFloatValue2 > 0.0f) {
                float f13 = 2;
                bitMap = Bitmap.createBitmap(bitMap, J7.a.K(fFloatValue / f13), J7.a.K(fFloatValue2 / f13), width, height);
                kotlin.jvm.internal.l.d(bitMap, "createBitmap(...)");
            }
        }
        if (imageColorizer.isAutoScale()) {
            thermalImage2.getScale().setRangeMax(imageColorizer.getScaleRangeMax());
            thermalImage2.getScale().setRangeMin(imageColorizer.getScaleRangeMin());
        }
        if (u() == enumC2424l) {
            imageColorizer.close();
        }
        float f14 = f10;
        new Canvas(bitMap).drawBitmap(bitmap, f14, f14, (Paint) null);
        iVar.invoke(bitMap);
        ByteBuffer byteBufferAllocate = ByteBuffer.allocate(bitmap.getAllocationByteCount());
        kotlin.jvm.internal.l.d(byteBufferAllocate, "allocate(...)");
        bitMap.copyPixelsToBuffer(byteBufferAllocate);
        thermalImage3.saveAs(this.f25690d0, new JavaImageBuffer.Builder().format(ImageBuffer.Format.RGBA_8888).width(bitmap.getWidth()).height(bitmap.getHeight()).stride(bitmap.getWidth() * 4).pixelBuffer(byteBufferAllocate.array()).build());
        u();
        JavaImageBuffer photo2 = (this.f25708n || !z4 || (fusion = thermalImage3.getFusion()) == null) ? null : fusion.getPhoto();
        C0903t c0903tA2 = ((C1443b) aVar2).a();
        if (c0903tA2 != null) {
            m9.f fVar2 = f9.P.f16067a;
            f9.F.B(c0903tA2, m9.e.f20045a, null, new L(this, z4, photo2, aVar, null), 2);
        }
    }

    public final void k() {
        C0903t c0903tA = ((C1443b) this.f25693f).a();
        if (c0903tA != null) {
            m9.f fVar = f9.P.f16067a;
            f9.F.B(c0903tA, m9.e.f20045a, null, new N(this, null), 2);
        }
    }

    /* JADX WARN: Type inference failed for: r0v1, types: [java.lang.Object, t7.g] */
    public final AppCoreResourceTreeService l() {
        return (AppCoreResourceTreeService) this.f25707m0.getValue();
    }

    public final Size m() {
        return (Size) this.f25710o.getValue();
    }

    public final boolean n() {
        return v();
    }

    /* JADX WARN: Code restructure failed: missing block: B:21:0x0047, code lost:
    
        if (((r2 == null || (r2 = r2.getFusionController()) == null || (r2 = r2.distance()) == null) ? null : r2.setSync(java.lang.Double.valueOf(r5.f25719s0))) == null) goto L22;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public final double o() {
        if (this.f25719s0 == -1.0d) {
            String strC = ((C2720n) this.f25697h).c("resource_tree/fusion_alignment_distance");
            if (strC == null) {
                strC = "";
            }
            Double dM0 = c9.n.m0(strC);
            double dDoubleValue = dM0 != null ? dM0.doubleValue() : 2.0d;
            Camera camera = this.f25706m;
            if (camera != null) {
                this.f25719s0 = dDoubleValue;
                RemoteControl remoteControl = camera.getRemoteControl();
            }
            return dDoubleValue;
        }
        return this.f25719s0;
    }

    public final t7.k p() {
        return new t7.k(Double.valueOf(0.5d), Double.valueOf(5.1d));
    }

    public final GLSurfaceView q() {
        return (GLSurfaceView) this.f25721t0.getValue();
    }

    public final boolean r() {
        return (u() == EnumC2424l.LIVE) & this.f25727w0;
    }

    public final float s() throws NumberFormatException {
        float height;
        RemoteControl remoteControl;
        Property<Integer> propertyMinSpotSize;
        Integer sync;
        boolean zA = ((C2723q) this.i).a();
        y4.d dVar = this.f25697h;
        int i = 8;
        if (!zA) {
            Camera camera = this.f25706m;
            int iIntValue = (camera == null || (remoteControl = camera.getRemoteControl()) == null || (propertyMinSpotSize = remoteControl.minSpotSize()) == null || (sync = propertyMinSpotSize.getSync()) == null) ? 0 : sync.intValue();
            if (iIntValue == 0) {
                String strC = ((C2720n) dVar).c("resource_tree/image_adjust/min_spot_radius_cache");
                if (strC != null) {
                    i = Integer.parseInt(strC);
                }
            } else {
                i = iIntValue;
            }
        }
        EnumC2424l enumC2424lU = u();
        EnumC2424l enumC2424l = EnumC2424l.LIVE;
        if (enumC2424lU == enumC2424l && i != 0) {
            ((C2720n) dVar).e("resource_tree/image_adjust/min_spot_radius_cache", String.valueOf(i));
        }
        if (m().getWidth() != 0) {
            height = t().getHeight() / m().getHeight();
        } else if (u() == enumC2424l) {
            height = 1.0f;
        } else {
            ThermalImageFile thermalImageFile = this.f25663J;
            float width = thermalImageFile != null ? thermalImageFile.getWidth() : 720;
            height = v() ? (t().getHeight() * ((this.f25663J != null ? r1.getHeight() : 960) / width)) / width : t().getWidth() / width;
        }
        float f10 = (i * height * w().f23674a) + 5;
        float f11 = ((2 * f10) / this.f25714q) + 1;
        C0525k0 c0525k0 = this.f25660H0;
        c0525k0.l(f11);
        Log.d(f25644N0, "Spot radius: " + f10 + " px (" + c0525k0.k() + " dp)");
        return c0525k0.k();
    }

    public final Size t() {
        return (Size) this.f25725v0.getValue();
    }

    public final EnumC2424l u() {
        return (EnumC2424l) this.f25657G.getValue();
    }

    public final boolean v() {
        return ((Boolean) this.f25679W.getValue()).booleanValue();
    }

    public final synchronized t4.e0 w() {
        return (t4.e0) this.f25680X.getValue();
    }

    public final void x(ThermalImage thermalImage) {
        RemoteControl remoteControl;
        Calibration calibration;
        Property<Calibration.NucState> propertyNucState;
        B((((C2723q) this.i).a() || this.f25670N) ? new Size(thermalImage.getWidth(), thermalImage.getHeight()) : new Size(thermalImage.getHeight(), thermalImage.getWidth()));
        F(true);
        C2704D c2704d = (C2704D) this.f25685b;
        c2704d.i = false;
        c2704d.f25515n.setValue(Boolean.FALSE);
        try {
            Camera camera = this.f25706m;
            if (camera == null || !camera.isConnected()) {
                return;
            }
            Camera camera2 = this.f25706m;
            if (camera2 != null && (remoteControl = camera2.getRemoteControl()) != null && (calibration = remoteControl.getCalibration()) != null && (propertyNucState = calibration.nucState()) != null) {
                propertyNucState.subscribe(new H(this, 5));
            }
            Q q10 = this.f25717r0;
            if (q10 != null) {
                q10.cancel();
            }
            Timer timer = new Timer("cameraConnectionMonitor");
            Q q11 = new Q(this, 3);
            timer.schedule(q11, 2000L, 2000L);
            this.f25717r0 = q11;
        } catch (Exception e5) {
            Log.e(f25644N0, AbstractC0109q.B("initializeThermalImage(): Subscribe to NUC threw exception = ", e5.getMessage()));
        }
    }

    public final boolean y() {
        C2727v c2727v = (C2727v) this.f25691e;
        return c2727v.g() == EnumC2427o.AUTO || ((c2727v.f25780d != EnumC2725t.OneTouchIdle) && (u() != EnumC2424l.LIVE || !this.f25708n));
    }

    public final synchronized void z(ThermalImage thermalImage) {
        try {
            if (this.L) {
                return;
            }
            int i = J.f25536a[u().ordinal()];
            if (i == 1) {
                ThermalImageFile thermalImageFile = this.f25659H;
                if (thermalImageFile != null) {
                    U(thermalImageFile, false);
                }
            } else if (i == 2) {
                ThermalImageFile thermalImageFile2 = this.f25663J;
                if (thermalImageFile2 != null) {
                    U(thermalImageFile2, false);
                }
            } else {
                if (i != 3) {
                    throw new A8.M();
                }
                if (!this.f25708n) {
                    U(thermalImage, true);
                }
            }
        } catch (Throwable th) {
            throw th;
        }
    }
}
