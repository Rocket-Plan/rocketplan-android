package com.flir.earhart.common.viewModel;

import B.AbstractC0109q;
import H7.n;
import android.net.Uri;
import android.util.Log;
import c4.EnumC0979a;
import com.flir.earhart.common.composable.livecomponents.C1023b;
import com.flir.earhart.common.model.toolbar.ToolbarMainFactoryKt;
import com.flir.earhart.common.provider.CommonSaveProvider;
import com.flir.earhart.common.viewModel.ThermalStreamViewModel;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.remote.AppCoreResourceTreeService;
import com.flir.thermalsdk.live.streaming.Stream;
import com.flir.thermalsdk.live.streaming.ThermalStreamer;
import d4.C1194a;
import e4.c;
import f4.d;
import f9.C;
import f9.F;
import f9.P;
import i9.k0;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import kotlin.Metadata;
import kotlin.jvm.internal.l;
import m9.e;
import m9.f;
import org.json.JSONObject;
import q3.AbstractC2176a;
import r4.C2258a;
import t4.EnumC2419g;
import t4.InterfaceC2417e;
import t7.w;
import x4.C2707a;
import x4.C2716j;
import x4.C2719m;
import x4.H;
import x4.Q;
import x4.j0;
import x7.InterfaceC2738c;
import y7.EnumC2851a;
import z7.InterfaceC2888e;
import z7.i;

@Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\n"}, d2 = {"<anonymous>", "", "Lkotlinx/coroutines/CoroutineScope;"}, k = 3, mv = {2, 2, 0}, xi = 48)
@InterfaceC2888e(c = "com.flir.earhart.common.viewModel.ThermalStreamViewModel$connectCamera$1$1$1", f = "ThermalStreamViewModel.kt", l = {251}, m = "invokeSuspend")
/* loaded from: classes.dex */
public final class ThermalStreamViewModel$connectCamera$1$1$1 extends i implements n {
    final /* synthetic */ C2258a $camera;
    int I$0;
    Object L$0;
    int label;
    final /* synthetic */ ThermalStreamViewModel this$0;

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public ThermalStreamViewModel$connectCamera$1$1$1(ThermalStreamViewModel thermalStreamViewModel, C2258a c2258a, InterfaceC2738c<? super ThermalStreamViewModel$connectCamera$1$1$1> interfaceC2738c) {
        super(2, interfaceC2738c);
        this.this$0 = thermalStreamViewModel;
        this.$camera = c2258a;
    }

    @Override // z7.AbstractC2884a
    public final InterfaceC2738c<w> create(Object obj, InterfaceC2738c<?> interfaceC2738c) {
        return new ThermalStreamViewModel$connectCamera$1$1$1(this.this$0, this.$camera, interfaceC2738c);
    }

    /* JADX WARN: Type inference failed for: r0v29, types: [java.lang.Object, t7.g] */
    /* JADX WARN: Type inference failed for: r0v33, types: [java.lang.Object, t7.g] */
    @Override // z7.AbstractC2884a
    public final Object invokeSuspend(Object obj) throws Exception {
        d dVar;
        Object objCollectCameraProperties;
        Stream stream;
        Object next;
        EnumC2851a enumC2851a = EnumC2851a.COROUTINE_SUSPENDED;
        int i = this.label;
        if (i == 0) {
            AbstractC2176a.l(obj);
            EnumC2419g enumC2419g = this.this$0.currentConnectionType;
            EnumC2419g enumC2419g2 = EnumC2419g.FULL_STREAM;
            if (enumC2419g == enumC2419g2) {
                this.this$0.setupStreamCallback();
            }
            int i3 = (this.this$0.getUseOpenGL() || this.this$0.emulatorType == ThermalStreamViewModel.EmulatorType.EMULATOR_EARHART) ? 1 : 0;
            InterfaceC2417e interfaceC2417e = this.this$0.streamService;
            String string = this.$camera.f22517b.toString();
            l.d(string, "toString(...)");
            EnumC2419g connectionType = this.this$0.currentConnectionType;
            j0 j0Var = (j0) interfaceC2417e;
            j0Var.getClass();
            l.e(connectionType, "connectionType");
            Camera camera = (Camera) ((C2716j) j0Var.f25683a).f25643e.get(Uri.parse(string));
            j0Var.f25706m = camera;
            if (camera != null) {
                String str = j0.f25644N0;
                if (connectionType == enumC2419g2) {
                    List<Stream> streams = camera.getStreams();
                    if (streams != null) {
                        Iterator<T> it = streams.iterator();
                        while (true) {
                            if (!it.hasNext()) {
                                next = null;
                                break;
                            }
                            next = it.next();
                            if (((Stream) next).isThermal()) {
                                break;
                            }
                        }
                        stream = (Stream) next;
                    } else {
                        stream = null;
                    }
                    j0Var.f25704l = stream;
                    if (stream != null) {
                        if (!j0Var.f25708n) {
                            j0Var.f25724v = new ThermalStreamer(stream);
                            if (!stream.isStreaming()) {
                                ThermalStreamer thermalStreamer = j0Var.f25724v;
                                if (thermalStreamer != null) {
                                    thermalStreamer.withThermalImage(new H(j0Var, 3));
                                }
                                j0Var.K();
                            }
                        } else {
                            if (!j0.A("pipeline setup", new C1023b(camera, stream, j0Var, 4))) {
                                throw new Exception("GL pipeline setup failed");
                            }
                            try {
                                camera.glWithThermalImage(new H(j0Var, 2));
                            } catch (Exception e5) {
                                AbstractC0109q.A("streamFrom(): Exception = ", e5.getMessage(), str);
                            }
                            j0Var.M();
                        }
                        j0Var.L();
                    }
                }
                if (i3 != 0) {
                    Log.d(str, "Setup resource tree.");
                    j0Var.l().setCamera(camera);
                    C2707a c2707a = (C2707a) j0Var.f25709n0.getValue();
                    AppCoreResourceTreeService service = j0Var.l();
                    c2707a.getClass();
                    l.e(service, "service");
                    k0 k0Var = c2707a.f25575a;
                    k0Var.getClass();
                    k0Var.j(null, service);
                    C2719m c2719m = (C2719m) j0Var.f25711o0.getValue();
                    c2719m.f25757a = j0Var.l();
                    Iterator it2 = c2719m.f25758b.iterator();
                    while (it2.hasNext()) {
                        ((H7.a) it2.next()).invoke();
                    }
                }
                Q q10 = j0Var.f25715q0;
                if (q10 != null) {
                    q10.cancel();
                }
                Timer timer = new Timer("lateInitiationTimer");
                Q q11 = new Q(j0Var, 5);
                timer.schedule(q11, CommonSaveProvider.VIDEO_FILTER_TIMEOUT, CommonSaveProvider.VIDEO_FILTER_TIMEOUT);
                j0Var.f25715q0 = q11;
            }
            if (this.this$0.currentConnectionType == enumC2419g2) {
                ((j0) this.this$0.streamService).D(true);
            }
            Stream stream2 = ((j0) this.this$0.streamService).f25704l;
            if (stream2 != null && stream2.isStreaming()) {
                dVar = this.this$0.mixPanelAnalyticsService;
                ThermalStreamViewModel thermalStreamViewModel = this.this$0;
                this.L$0 = dVar;
                this.I$0 = i3;
                this.label = 1;
                objCollectCameraProperties = thermalStreamViewModel.collectCameraProperties(this);
                if (objCollectCameraProperties == enumC2851a) {
                    return enumC2851a;
                }
            }
            return w.f23807a;
        }
        if (i != 1) {
            throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
        }
        dVar = (d) this.L$0;
        AbstractC2176a.l(obj);
        objCollectCameraProperties = obj;
        C1194a cameraPropertiesModel = (C1194a) objCollectCameraProperties;
        e4.d dVar2 = (e4.d) dVar;
        dVar2.getClass();
        l.e(cameraPropertiesModel, "cameraPropertiesModel");
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("ir_scale", cameraPropertiesModel.f15202a);
        jSONObject.put(ToolbarMainFactoryKt.PALETTE, cameraPropertiesModel.f15203b);
        jSONObject.put("measurement_preset", cameraPropertiesModel.f15204c);
        jSONObject.put("image_mode", cameraPropertiesModel.f15205d);
        jSONObject.put("temperature_range", cameraPropertiesModel.f15206e);
        jSONObject.put(ToolbarMainFactoryKt.REC_MODE, cameraPropertiesModel.f15207f);
        jSONObject.put("object_distance", cameraPropertiesModel.f15208g);
        jSONObject.put("atmospheric_temperature", cameraPropertiesModel.f15209h);
        jSONObject.put("relative_humidity", cameraPropertiesModel.i);
        jSONObject.put("reflected_temperature", cameraPropertiesModel.f15210j);
        jSONObject.put("emissivity", cameraPropertiesModel.f15211k);
        jSONObject.put("zoom", cameraPropertiesModel.f15212l);
        jSONObject.put("fullscreen_mode", cameraPropertiesModel.f15213m);
        jSONObject.put("overlay", cameraPropertiesModel.f15214n);
        EnumC0979a enumC0979a = EnumC0979a.LIVE_VIEW_VIEWED;
        f fVar = P.f16067a;
        F.B(dVar2.f15772d, e.f20045a, null, new c(dVar2, enumC0979a, jSONObject, null), 2);
        return w.f23807a;
    }

    @Override // H7.n
    public final Object invoke(C c10, InterfaceC2738c<? super w> interfaceC2738c) {
        return ((ThermalStreamViewModel$connectCamera$1$1$1) create(c10, interfaceC2738c)).invokeSuspend(w.f23807a);
    }
}
