package com.flir.earhart.common.viewModel;

import H7.k;
import H7.n;
import android.util.Log;
import f9.C;
import f9.C1339n;
import f9.F;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Metadata;
import kotlin.jvm.internal.l;
import p8.AbstractC2147b;
import q3.AbstractC2176a;
import r4.C2258a;
import t4.EnumC2419g;
import t4.InterfaceC2415c;
import t7.w;
import x4.C2712f;
import x4.C2716j;
import x7.InterfaceC2738c;
import y7.EnumC2851a;
import z7.InterfaceC2888e;
import z7.i;

@Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0018\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u0004\u0018\u00010\u0001*\u00020\u0002H\n"}, d2 = {"<anonymous>", "Lcom/flir/modulex/thermal/model/Camera;", "Lkotlinx/coroutines/CoroutineScope;"}, k = 3, mv = {2, 2, 0}, xi = 48)
@InterfaceC2888e(c = "com.flir.earhart.common.viewModel.ThermalStreamViewModel$connectCamera$1$1$retCamera$1", f = "ThermalStreamViewModel.kt", l = {357}, m = "invokeSuspend")
/* loaded from: classes.dex */
public final class ThermalStreamViewModel$connectCamera$1$1$retCamera$1 extends i implements n {
    final /* synthetic */ C2258a $camera;
    int I$0;
    Object L$0;
    Object L$1;
    int label;
    final /* synthetic */ ThermalStreamViewModel this$0;

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public ThermalStreamViewModel$connectCamera$1$1$retCamera$1(ThermalStreamViewModel thermalStreamViewModel, C2258a c2258a, InterfaceC2738c<? super ThermalStreamViewModel$connectCamera$1$1$retCamera$1> interfaceC2738c) {
        super(2, interfaceC2738c);
        this.this$0 = thermalStreamViewModel;
        this.$camera = c2258a;
    }

    @Override // z7.AbstractC2884a
    public final InterfaceC2738c<w> create(Object obj, InterfaceC2738c<?> interfaceC2738c) {
        return new ThermalStreamViewModel$connectCamera$1$1$retCamera$1(this.this$0, this.$camera, interfaceC2738c);
    }

    @Override // z7.AbstractC2884a
    public final Object invokeSuspend(Object obj) {
        EnumC2851a enumC2851a = EnumC2851a.COROUTINE_SUSPENDED;
        int i = this.label;
        if (i != 0) {
            if (i != 1) {
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
            AbstractC2176a.l(obj);
            return obj;
        }
        AbstractC2176a.l(obj);
        ThermalStreamViewModel thermalStreamViewModel = this.this$0;
        C2258a camera = this.$camera;
        this.L$0 = thermalStreamViewModel;
        this.L$1 = camera;
        this.I$0 = 0;
        this.label = 1;
        final C1339n c1339n = new C1339n(1, AbstractC2147b.f(this));
        c1339n.t();
        final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
        InterfaceC2415c interfaceC2415c = thermalStreamViewModel.cameraScanService;
        EnumC2419g connectionType = thermalStreamViewModel.currentConnectionType;
        k kVar = new k() { // from class: com.flir.earhart.common.viewModel.ThermalStreamViewModel$connectCamera$1$1$retCamera$1$1$1
            @Override // H7.k
            public /* bridge */ /* synthetic */ Object invoke(Object obj2) {
                invoke((C2258a) obj2);
                return w.f23807a;
            }

            public final void invoke(C2258a c2258a) {
                if (atomicBoolean.compareAndSet(false, true)) {
                    c1339n.c(c2258a, null);
                } else {
                    Log.w(ThermalStreamViewModel.INSTANCE.getTAG(), "Ignoring duplicate resume from connectToCamera");
                }
            }
        };
        C2716j c2716j = (C2716j) interfaceC2415c;
        c2716j.getClass();
        l.e(camera, "camera");
        l.e(connectionType, "connectionType");
        F.B(c2716j.f25639a, null, null, new C2712f(c2716j, camera, connectionType, kVar, null), 3);
        Object objS = c1339n.s();
        return objS == enumC2851a ? enumC2851a : objS;
    }

    @Override // H7.n
    public final Object invoke(C c10, InterfaceC2738c<? super C2258a> interfaceC2738c) {
        return ((ThermalStreamViewModel$connectCamera$1$1$retCamera$1) create(c10, interfaceC2738c)).invokeSuspend(w.f23807a);
    }
}
