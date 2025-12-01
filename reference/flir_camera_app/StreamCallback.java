package x4;

import android.opengl.GLSurfaceView;
import android.util.Log;
import android.util.Size;
import com.flir.thermalsdk.image.ImageBase;
import com.flir.thermalsdk.image.ImageColorizer;
import com.flir.thermalsdk.image.ImageFactory;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.image.ThermalImageFile;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.remote.Calibration;
import com.flir.thermalsdk.live.remote.OnReceived;
import com.flir.thermalsdk.live.streaming.ThermalStreamer;
import com.flir.thermalsdk.utils.Consumer;
import java.io.IOException;
import java.util.Timer;
import t4.EnumC2427o;

/* loaded from: classes.dex */
public final /* synthetic */ class H implements OnReceived, Consumer {

    /* renamed from: a, reason: collision with root package name */
    public final /* synthetic */ int f25528a;

    /* renamed from: b, reason: collision with root package name */
    public final /* synthetic */ j0 f25529b;

    public /* synthetic */ H(j0 j0Var, int i) {
        this.f25528a = i;
        this.f25529b = j0Var;
    }

    @Override // com.flir.thermalsdk.utils.Consumer
    public void accept(Object obj) throws IOException {
        j0 j0Var = this.f25529b;
        ThermalImage thermalImage = (ThermalImage) obj;
        switch (this.f25528a) {
            case 1:
                kotlin.jvm.internal.l.b(thermalImage);
                j0Var.z(thermalImage);
                break;
            case 2:
                kotlin.jvm.internal.l.b(thermalImage);
                j0Var.x(thermalImage);
                break;
            case 3:
                kotlin.jvm.internal.l.b(thermalImage);
                j0Var.x(thermalImage);
                break;
            case 4:
            case 5:
            default:
                kotlin.jvm.internal.l.b(thermalImage);
                thermalImage.saveAs(j0Var.f25690d0);
                ImageBase imageBaseCreateImage = ImageFactory.createImage(j0Var.f25690d0);
                kotlin.jvm.internal.l.c(imageBaseCreateImage, "null cannot be cast to non-null type com.flir.thermalsdk.image.ThermalImageFile");
                ThermalImageFile thermalImageFile = (ThermalImageFile) imageBaseCreateImage;
                j0Var.f25659H = thermalImageFile;
                j0Var.f25661I = new ImageColorizer(thermalImageFile);
                break;
            case 6:
                Size size = new Size(thermalImage.getWidth(), thermalImage.getHeight());
                String str = j0.f25644N0;
                j0Var.B(size);
                break;
            case 7:
                kotlin.jvm.internal.l.b(thermalImage);
                thermalImage.saveAs(j0Var.f25690d0);
                ImageBase imageBaseCreateImage2 = ImageFactory.createImage(j0Var.f25690d0);
                kotlin.jvm.internal.l.c(imageBaseCreateImage2, "null cannot be cast to non-null type com.flir.thermalsdk.image.ThermalImageFile");
                ThermalImageFile thermalImageFile2 = (ThermalImageFile) imageBaseCreateImage2;
                j0Var.f25659H = thermalImageFile2;
                j0Var.f25661I = new ImageColorizer(thermalImageFile2);
                break;
        }
    }

    @Override // com.flir.thermalsdk.live.remote.OnReceived
    public void onReceived(Object obj) {
        switch (this.f25528a) {
            case 0:
                j0 j0Var = this.f25529b;
                ThermalStreamer thermalStreamer = j0Var.f25724v;
                if (thermalStreamer != null) {
                    thermalStreamer.setRenderScale(true);
                    thermalStreamer.withThermalImage(new H(j0Var, 1));
                    thermalStreamer.update();
                    break;
                }
                break;
            case 4:
                Integer num = (Integer) obj;
                j0 j0Var2 = this.f25529b;
                Q q10 = j0Var2.f25722u;
                if (q10 != null) {
                    q10.cancel();
                }
                j0Var2.f25722u = null;
                int iK = j0Var2.f25652D0.k();
                if (num == null || num.intValue() != iK) {
                    Log.d(j0.f25644N0, "New index directly received: " + num);
                    kotlin.jvm.internal.l.b(num);
                    j0Var2.S(num.intValue());
                    break;
                } else {
                    Timer timer = new Timer("rangeDebugTimer");
                    Q q11 = new Q(j0Var2, 6);
                    timer.schedule(q11, 3330L, 3330L);
                    j0Var2.f25722u = q11;
                    break;
                }
                break;
            case 5:
                Calibration.NucState nucState = (Calibration.NucState) obj;
                i9.k0 k0Var = this.f25529b.f25656F0;
                Boolean boolValueOf = Boolean.valueOf(nucState == Calibration.NucState.PROGRESS);
                k0Var.getClass();
                k0Var.j(null, boolValueOf);
                break;
            default:
                j0 j0Var3 = this.f25529b;
                int i = j0Var3.f25671O + 1;
                j0Var3.f25671O = i;
                y4.f fVar = j0Var3.f25691e;
                if (i == 2) {
                    Log.d(j0.f25644N0, "startOpenGlStream: stream started (frame 2): setting AUTO");
                    Camera camera = j0Var3.f25706m;
                    if (camera != null) {
                        camera.glScaleAutoAdjust(true);
                    }
                    C2727v c2727v = (C2727v) fVar;
                    c2727v.f25779c.f25765b = false;
                    c2727v.i.f25741h = true;
                } else if (i > 30) {
                    C2727v c2727v2 = (C2727v) fVar;
                    if (c2727v2.f25779c.f25765b) {
                        Log.d(j0.f25644N0, "startOpenGlStream: stream started (frame > 30): setting " + c2727v2.g());
                        Camera camera2 = j0Var3.f25706m;
                        if (camera2 != null) {
                            camera2.glScaleAutoAdjust(c2727v2.g() == EnumC2427o.AUTO);
                        }
                        c2727v2.f25779c.f25765b = false;
                    }
                }
                GLSurfaceView gLSurfaceViewQ = j0Var3.q();
                if (gLSurfaceViewQ != null) {
                    gLSurfaceViewQ.requestRender();
                    break;
                }
                break;
        }
    }
}
