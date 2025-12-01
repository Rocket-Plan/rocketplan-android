package com.flir.earhart.common.composable.livecomponents;

import N.C0376p1;
import Q.InterfaceC0515f0;
import com.flir.earhart.common.composable.toolbar.ToolbarPopupContentKt;
import com.flir.earhart.common.model.toolbar.ToolbarViewModel;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.streaming.Stream;
import f9.C;
import java.util.List;
import okhttp3.Address;
import okhttp3.CertificatePinner;
import okhttp3.Handshake;
import okhttp3.internal.connection.ConnectPlan;
import okhttp3.internal.tls.CertificateChainCleaner;
import x4.j0;

/* renamed from: com.flir.earhart.common.composable.livecomponents.b, reason: case insensitive filesystem */
/* loaded from: classes.dex */
public final /* synthetic */ class C1023b implements H7.a {

    /* renamed from: a, reason: collision with root package name */
    public final /* synthetic */ int f13812a;

    /* renamed from: b, reason: collision with root package name */
    public final /* synthetic */ Object f13813b;

    /* renamed from: c, reason: collision with root package name */
    public final /* synthetic */ Object f13814c;

    /* renamed from: d, reason: collision with root package name */
    public final /* synthetic */ Object f13815d;

    public /* synthetic */ C1023b(C c10, Object obj, Object obj2, int i) {
        this.f13812a = i;
        this.f13813b = c10;
        this.f13814c = obj;
        this.f13815d = obj2;
    }

    /* JADX WARN: Type inference failed for: r1v0, types: [java.lang.Object, t7.g] */
    @Override // H7.a
    public final Object invoke() throws NoSuchFieldException, SecurityException {
        Object obj = this.f13813b;
        ?? r12 = this.f13815d;
        Object obj2 = this.f13814c;
        switch (this.f13812a) {
            case 0:
                return ActionBottomBarKt.ActionBottomBar$lambda$9$lambda$7$lambda$6((V4.x) obj2, (List) r12, (C) obj);
            case 1:
                return StatusIconsKt.StatusIcons$lambda$6$lambda$5((C) obj, (InterfaceC0515f0) obj2, r12);
            case 2:
                return ToolbarPopupContentKt.ToolbarPopupContent$lambda$1$lambda$0((C) obj, (ToolbarViewModel) obj2, (C0376p1) r12);
            case 3:
                int i = ConnectPlan.L;
                CertificateChainCleaner certificateChainCleaner = ((CertificatePinner) obj2).f21036b;
                kotlin.jvm.internal.l.b(certificateChainCleaner);
                return certificateChainCleaner.a(((Address) obj).f21009h.f21114d, ((Handshake) r12).a());
            default:
                ((Camera) obj2).glSetupPipeline((Stream) r12, false);
                ((j0) obj).F(true);
                return t7.w.f23807a;
        }
    }

    public /* synthetic */ C1023b(Object obj, Object obj2, Object obj3, int i) {
        this.f13812a = i;
        this.f13814c = obj;
        this.f13815d = obj2;
        this.f13813b = obj3;
    }
}
