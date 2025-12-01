package com.flir.earhart.common.viewModel;

import H7.n;
import Q.C0508c;
import Q.C0525k0;
import Q.InterfaceC0509c0;
import Q.InterfaceC0515f0;
import Q.g1;
import android.app.Application;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.util.Log;
import androidx.lifecycle.AbstractC0885a;
import androidx.lifecycle.C0903t;
import androidx.lifecycle.T;
import com.flir.earhart.common.composable.C1032q;
import com.flir.earhart.common.model.temp.NamedTempUnit;
import com.flir.earhart.common.model.toolbar.ToolbarExtentionsKt;
import com.flir.earhart.common.notifications.NotificationsProvider;
import com.flir.earhart.common.provider.CommonStatusProvider;
import com.flir.earhart.common.service.CommonSettingsService;
import com.flir.earhart.common.service.CommonStatusService;
import com.flir.thermalsdk.live.streaming.Stream;
import d4.C1194a;
import f4.d;
import f9.C;
import f9.F;
import f9.P;
import g4.C1443b;
import j0.C1641d;
import j0.InterfaceC1627A;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Metadata;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.l;
import m9.f;
import o9.InterfaceC2087a;
import q3.AbstractC2176a;
import r1.h;
import r4.C2258a;
import r4.c;
import s4.C2340a;
import s4.D;
import s4.m;
import t4.EnumC2410A;
import t4.EnumC2411B;
import t4.EnumC2419g;
import t4.EnumC2424l;
import t4.InterfaceC2415c;
import t4.InterfaceC2417e;
import t4.K;
import t4.M;
import t4.V;
import t4.Y;
import t4.b0;
import t7.w;
import x4.C2715i;
import x4.C2716j;
import x4.C2720n;
import x4.C2723q;
import x4.C2724s;
import x4.C2727v;
import x4.C2730y;
import x4.a0;
import x4.j0;
import x7.InterfaceC2738c;
import y4.e;
import y7.EnumC2851a;
import z7.AbstractC2886c;
import z7.InterfaceC2888e;
import z7.i;

@Metadata(d1 = {"\u0000Æ\u0001\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000b\n\u0000\n\u0002\u0018\u0002\n\u0002\b\r\n\u0002\u0018\u0002\n\u0002\b\n\n\u0002\u0010\u0007\n\u0002\b\u000b\n\u0002\u0018\u0002\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0006\b\u0007\u0018\u0000 n2\u00020\u0001:\u0002noBg\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u0012\u0006\u0010\u0006\u001a\u00020\u0007\u0012\u0006\u0010\b\u001a\u00020\t\u0012\u0006\u0010\n\u001a\u00020\u000b\u0012\u0006\u0010\f\u001a\u00020\r\u0012\u0006\u0010\u000e\u001a\u00020\u000f\u0012\u0006\u0010\u0010\u001a\u00020\u0011\u0012\u0006\u0010\u0012\u001a\u00020\u0013\u0012\u0006\u0010\u0014\u001a\u00020\u0015\u0012\u0006\u0010\u0016\u001a\u00020\u0017\u0012\u0006\u0010\u0018\u001a\u00020\u0019¢\u0006\u0004\b\u001a\u0010\u001bJ\u0016\u0010V\u001a\u00020W2\u0006\u0010X\u001a\u00020\"2\u0006\u0010Y\u001a\u00020$J\b\u0010Z\u001a\u00020WH\u0002J\b\u0010[\u001a\u00020WH\u0002J\b\u0010\\\u001a\u00020WH\u0002J \u0010]\u001a\u00020W2\u0010\b\u0002\u0010^\u001a\n\u0012\u0004\u0012\u00020W\u0018\u00010_H\u0086@¢\u0006\u0002\u0010`J\b\u0010a\u001a\u00020WH\u0002J\b\u0010b\u001a\u00020cH\u0002J\u0018\u0010d\u001a\u00020W2\u0006\u0010e\u001a\u00020f2\b\b\u0002\u0010g\u001a\u00020hJ\u000e\u0010i\u001a\u00020jH\u0082@¢\u0006\u0002\u0010kJ\b\u0010l\u001a\u00020WH\u0002J\b\u0010m\u001a\u00020WH\u0002R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\u0004\u001a\u00020\u0005X\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\tX\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\n\u001a\u00020\u000bX\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\f\u001a\u00020\rX\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\u000e\u001a\u00020\u000fX\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\u0010\u001a\u00020\u0011X\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\u0012\u001a\u00020\u0013X\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\u0014\u001a\u00020\u0015X\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\u0016\u001a\u00020\u0017X\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\u001c\u001a\u00020\u001dX\u0082\u000e¢\u0006\u0002\n\u0000R\u0010\u0010\u001e\u001a\u0004\u0018\u00010\u001fX\u0082\u000e¢\u0006\u0002\n\u0000R\u0010\u0010 \u001a\u0004\u0018\u00010\u001fX\u0082\u000e¢\u0006\u0002\n\u0000R\u000e\u0010!\u001a\u00020\"X\u0082\u000e¢\u0006\u0002\n\u0000R\u000e\u0010#\u001a\u00020$X\u0082\u000e¢\u0006\u0002\n\u0000R/\u0010'\u001a\u0004\u0018\u00010&2\b\u0010%\u001a\u0004\u0018\u00010&8B@BX\u0082\u008e\u0002¢\u0006\u0012\n\u0004\b,\u0010-\u001a\u0004\b(\u0010)\"\u0004\b*\u0010+R+\u0010.\u001a\u00020$2\u0006\u0010%\u001a\u00020$8F@FX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\b3\u0010-\u001a\u0004\b/\u00100\"\u0004\b1\u00102R/\u00105\u001a\u0004\u0018\u0001042\b\u0010%\u001a\u0004\u0018\u0001048F@FX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\b:\u0010-\u001a\u0004\b6\u00107\"\u0004\b8\u00109R/\u0010;\u001a\u0004\u0018\u0001042\b\u0010%\u001a\u0004\u0018\u0001048F@FX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\b>\u0010-\u001a\u0004\b<\u00107\"\u0004\b=\u00109R+\u0010@\u001a\u00020?2\u0006\u0010%\u001a\u00020?8F@FX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\bE\u0010F\u001a\u0004\bA\u0010B\"\u0004\bC\u0010DR+\u0010G\u001a\u00020?2\u0006\u0010%\u001a\u00020?8F@FX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\bJ\u0010F\u001a\u0004\bH\u0010B\"\u0004\bI\u0010DR7\u0010L\u001a\b\u0012\u0004\u0012\u00020$0K2\f\u0010%\u001a\b\u0012\u0004\u0012\u00020$0K8F@FX\u0086\u008e\u0002¢\u0006\u0012\n\u0004\bQ\u0010-\u001a\u0004\bM\u0010N\"\u0004\bO\u0010PR\u000e\u0010R\u001a\u00020SX\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010T\u001a\u00020UX\u0082\u0004¢\u0006\u0002\n\u0000¨\u0006p"}, d2 = {"Lcom/flir/earhart/common/viewModel/ThermalStreamViewModel;", "Landroidx/lifecycle/AndroidViewModel;", "cameraScanService", "Lcom/flir/modulex/thermal/service/CameraScanService;", "streamService", "Lcom/flir/modulex/thermal/service/CameraStreamService;", "tsdkEmulatorService", "Lcom/flir/modulex/tsdk/service/TSDKEmulatorService;", "mixPanelAnalyticsService", "Lcom/flir/modulex/mixpanel/service/MixPanelAnalyticsService;", "tsdkImageAdjustSettingsService", "Lcom/flir/modulex/thermal/service/TSDKImageAdjustSettingsService;", "thermalImageAdjustService", "Lcom/flir/modulex/thermal/service/ThermalImageAdjustService;", "thermalPaletteService", "Lcom/flir/modulex/thermal/service/ThermalPaletteService;", "thermalMeasurementService", "Lcom/flir/modulex/thermal/service/ThermalMeasurementService;", "saveThermalService", "Lcom/flir/modulex/thermal/service/SaveThermalService;", "commonStatusService", "Lcom/flir/earhart/common/service/CommonStatusService;", "commonSettingsService", "Lcom/flir/earhart/common/service/CommonSettingsService;", "application", "Landroid/app/Application;", "<init>", "(Lcom/flir/modulex/thermal/service/CameraScanService;Lcom/flir/modulex/thermal/service/CameraStreamService;Lcom/flir/modulex/tsdk/service/TSDKEmulatorService;Lcom/flir/modulex/mixpanel/service/MixPanelAnalyticsService;Lcom/flir/modulex/thermal/service/TSDKImageAdjustSettingsService;Lcom/flir/modulex/thermal/service/ThermalImageAdjustService;Lcom/flir/modulex/thermal/service/ThermalPaletteService;Lcom/flir/modulex/thermal/service/ThermalMeasurementService;Lcom/flir/modulex/thermal/service/SaveThermalService;Lcom/flir/earhart/common/service/CommonStatusService;Lcom/flir/earhart/common/service/CommonSettingsService;Landroid/app/Application;)V", "emulatorType", "Lcom/flir/earhart/common/viewModel/ThermalStreamViewModel$EmulatorType;", "currentCamera", "Lcom/flir/modulex/thermal/model/Camera;", "currentEmulator", "currentConnectionType", "Lcom/flir/modulex/thermal/service/ConnectionType;", "currentUseEmulatorTypeEarhart", "", "<set-?>", "Ljava/util/TimerTask;", "reconnectionTimer", "getReconnectionTimer", "()Ljava/util/TimerTask;", "setReconnectionTimer", "(Ljava/util/TimerTask;)V", "reconnectionTimer$delegate", "Landroidx/compose/runtime/MutableState;", "useOpenGL", "getUseOpenGL", "()Z", "setUseOpenGL", "(Z)V", "useOpenGL$delegate", "Landroidx/compose/ui/graphics/ImageBitmap;", "imageBitmap", "getImageBitmap", "()Landroidx/compose/ui/graphics/ImageBitmap;", "setImageBitmap", "(Landroidx/compose/ui/graphics/ImageBitmap;)V", "imageBitmap$delegate", "scaleBitmap", "getScaleBitmap", "setScaleBitmap", "scaleBitmap$delegate", "", "scaleHigh", "getScaleHigh", "()F", "setScaleHigh", "(F)V", "scaleHigh$delegate", "Landroidx/compose/runtime/MutableFloatState;", "scaleLow", "getScaleLow", "setScaleLow", "scaleLow$delegate", "Landroidx/compose/runtime/State;", "longInitiationOfStream", "getLongInitiationOfStream", "()Landroidx/compose/runtime/State;", "setLongInitiationOfStream", "(Landroidx/compose/runtime/State;)V", "longInitiationOfStream$delegate", "isConnecting", "Ljava/util/concurrent/atomic/AtomicBoolean;", "disconnectMutex", "Lkotlinx/coroutines/sync/Mutex;", "connect", "", "connectionType", "useEmulatorTypeEarhart", "startConnectionProcess", "cameraScan", "connectCamera", "disconnect", "disconnectCompleted", "Lkotlin/Function0;", "(Lkotlin/jvm/functions/Function0;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "clear", "timeSinceLastDisconnection", "", "recall", "fileUriString", "", "recallUriType", "Lcom/flir/modulex/thermal/service/RecallUriType;", "collectCameraProperties", "Lcom/flir/modulex/mixpanel/model/CameraPropertiesModel;", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "setupStreamCallback", "connectToEmulator", "Companion", "EmulatorType", "library_release"}, k = 1, mv = {2, 2, 0}, xi = 48)
/* loaded from: classes.dex */
public final class ThermalStreamViewModel extends AbstractC0885a {
    public static final long RECONNECTION_INTERVAL = 2000;
    private final InterfaceC2415c cameraScanService;
    private final CommonSettingsService commonSettingsService;
    private final CommonStatusService commonStatusService;
    private C2258a currentCamera;
    private EnumC2419g currentConnectionType;
    private C2258a currentEmulator;
    private boolean currentUseEmulatorTypeEarhart;
    private final InterfaceC2087a disconnectMutex;
    private EmulatorType emulatorType;
    private final InterfaceC0515f0 imageBitmap$delegate;
    private final AtomicBoolean isConnecting;
    private final InterfaceC0515f0 longInitiationOfStream$delegate;
    private final d mixPanelAnalyticsService;
    private final InterfaceC0515f0 reconnectionTimer$delegate;
    private final K saveThermalService;
    private final InterfaceC0515f0 scaleBitmap$delegate;
    private final InterfaceC0509c0 scaleHigh$delegate;
    private final InterfaceC0509c0 scaleLow$delegate;
    private final InterfaceC2417e streamService;
    private final V thermalImageAdjustService;
    private final Y thermalMeasurementService;
    private final b0 thermalPaletteService;
    private final e tsdkEmulatorService;
    private final M tsdkImageAdjustSettingsService;
    private final InterfaceC0515f0 useOpenGL$delegate;

    /* renamed from: Companion, reason: from kotlin metadata */
    public static final Companion INSTANCE = new Companion(null);
    public static final int $stable = 8;
    private static final String TAG = "ThermalStreamViewModel";

    @Metadata(d1 = {"\u0000\u001a\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0002\b\u0003\n\u0002\u0010\t\n\u0000\b\u0086\u0003\u0018\u00002\u00020\u0001B\t\b\u0002¢\u0006\u0004\b\u0002\u0010\u0003R\u0011\u0010\u0004\u001a\u00020\u0005¢\u0006\b\n\u0000\u001a\u0004\b\u0006\u0010\u0007R\u000e\u0010\b\u001a\u00020\tX\u0086T¢\u0006\u0002\n\u0000¨\u0006\n"}, d2 = {"Lcom/flir/earhart/common/viewModel/ThermalStreamViewModel$Companion;", "", "<init>", "()V", NotificationsProvider.TAG, "", "getTAG", "()Ljava/lang/String;", "RECONNECTION_INTERVAL", "", "library_release"}, k = 1, mv = {2, 2, 0}, xi = 48)
    public static final class Companion {
        public /* synthetic */ Companion(DefaultConstructorMarker defaultConstructorMarker) {
            this();
        }

        public final String getTAG() {
            return ThermalStreamViewModel.TAG;
        }

        private Companion() {
        }
    }

    /* JADX WARN: Failed to restore enum class, 'enum' modifier and super class removed */
    /* JADX WARN: Unknown enum class pattern. Please report as an issue! */
    @Metadata(d1 = {"\u0000\f\n\u0002\u0018\u0002\n\u0002\u0010\u0010\n\u0002\b\u0005\b\u0086\u0081\u0002\u0018\u00002\b\u0012\u0004\u0012\u00020\u00000\u0001B\t\b\u0002¢\u0006\u0004\b\u0002\u0010\u0003j\u0002\b\u0004j\u0002\b\u0005¨\u0006\u0006"}, d2 = {"Lcom/flir/earhart/common/viewModel/ThermalStreamViewModel$EmulatorType;", "", "<init>", "(Ljava/lang/String;I)V", "EMULATOR_EARHART", "EMULATOR_FLIR_ONE", "library_release"}, k = 1, mv = {2, 2, 0}, xi = 48)
    public static final class EmulatorType {
        private static final /* synthetic */ A7.a $ENTRIES;
        private static final /* synthetic */ EmulatorType[] $VALUES;
        public static final EmulatorType EMULATOR_EARHART = new EmulatorType("EMULATOR_EARHART", 0);
        public static final EmulatorType EMULATOR_FLIR_ONE = new EmulatorType("EMULATOR_FLIR_ONE", 1);

        private static final /* synthetic */ EmulatorType[] $values() {
            return new EmulatorType[]{EMULATOR_EARHART, EMULATOR_FLIR_ONE};
        }

        static {
            EmulatorType[] emulatorTypeArr$values = $values();
            $VALUES = emulatorTypeArr$values;
            $ENTRIES = android.support.v4.media.session.b.u(emulatorTypeArr$values);
        }

        private EmulatorType(String str, int i) {
        }

        public static A7.a getEntries() {
            return $ENTRIES;
        }

        public static EmulatorType valueOf(String str) {
            return (EmulatorType) Enum.valueOf(EmulatorType.class, str);
        }

        public static EmulatorType[] values() {
            return (EmulatorType[]) $VALUES.clone();
        }
    }

    @InterfaceC2888e(c = "com.flir.earhart.common.viewModel.ThermalStreamViewModel", f = "ThermalStreamViewModel.kt", l = {310}, m = "collectCameraProperties")
    @Metadata(k = 3, mv = {2, 2, 0}, xi = 48)
    /* renamed from: com.flir.earhart.common.viewModel.ThermalStreamViewModel$collectCameraProperties$1, reason: invalid class name */
    public static final class AnonymousClass1 extends AbstractC2886c {
        Object L$0;
        Object L$1;
        Object L$2;
        Object L$3;
        Object L$4;
        Object L$5;
        int label;
        /* synthetic */ Object result;

        public AnonymousClass1(InterfaceC2738c<? super AnonymousClass1> interfaceC2738c) {
            super(interfaceC2738c);
        }

        @Override // z7.AbstractC2884a
        public final Object invokeSuspend(Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return ThermalStreamViewModel.this.collectCameraProperties(this);
        }
    }

    @Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\n"}, d2 = {"<anonymous>", "", "Lkotlinx/coroutines/CoroutineScope;"}, k = 3, mv = {2, 2, 0}, xi = 48)
    @InterfaceC2888e(c = "com.flir.earhart.common.viewModel.ThermalStreamViewModel$connectCamera$1", f = "ThermalStreamViewModel.kt", l = {201, 234}, m = "invokeSuspend")
    /* renamed from: com.flir.earhart.common.viewModel.ThermalStreamViewModel$connectCamera$1, reason: invalid class name and case insensitive filesystem */
    public static final class C11371 extends i implements n {
        int I$0;
        Object L$0;
        Object L$1;
        Object L$2;
        int label;

        public C11371(InterfaceC2738c<? super C11371> interfaceC2738c) {
            super(2, interfaceC2738c);
        }

        @Override // z7.AbstractC2884a
        public final InterfaceC2738c<w> create(Object obj, InterfaceC2738c<?> interfaceC2738c) {
            return ThermalStreamViewModel.this.new C11371(interfaceC2738c);
        }

        /* JADX WARN: Removed duplicated region for block: B:24:0x00cf  */
        /* JADX WARN: Removed duplicated region for block: B:26:0x00dc  */
        @Override // z7.AbstractC2884a
        /*
            Code decompiled incorrectly, please refer to instructions dump.
        */
        public final Object invokeSuspend(Object obj) {
            ThermalStreamViewModel thermalStreamViewModel;
            C2258a c2258a;
            int i;
            EnumC2851a enumC2851a = EnumC2851a.COROUTINE_SUSPENDED;
            int i3 = this.label;
            w wVar = w.f23807a;
            if (i3 == 0) {
                AbstractC2176a.l(obj);
                Companion companion = ThermalStreamViewModel.INSTANCE;
                Log.d(companion.getTAG(), "Starting connectCamera flow");
                if (!ThermalStreamViewModel.this.isConnecting.get()) {
                    Log.d(companion.getTAG(), "Disconnect was called → abort connection");
                    ThermalStreamViewModel.this.clear();
                    return wVar;
                }
                C2258a c2258a2 = ThermalStreamViewModel.this.currentCamera;
                if (c2258a2 == null) {
                    c2258a2 = ThermalStreamViewModel.this.currentEmulator;
                }
                if (c2258a2 != null) {
                    thermalStreamViewModel = ThermalStreamViewModel.this;
                    Log.d(companion.getTAG(), "Connect: Camera connect thermal stream view model: " + c2258a2);
                    f fVar = P.f16067a;
                    m9.e eVar = m9.e.f20045a;
                    ThermalStreamViewModel$connectCamera$1$1$retCamera$1 thermalStreamViewModel$connectCamera$1$1$retCamera$1 = new ThermalStreamViewModel$connectCamera$1$1$retCamera$1(thermalStreamViewModel, c2258a2, null);
                    this.L$0 = null;
                    this.L$1 = thermalStreamViewModel;
                    this.L$2 = c2258a2;
                    this.I$0 = 0;
                    this.label = 1;
                    obj = F.L(eVar, thermalStreamViewModel$connectCamera$1$1$retCamera$1, this);
                    if (obj != enumC2851a) {
                        c2258a = c2258a2;
                        i = 0;
                        C2258a c2258a3 = (C2258a) obj;
                        Companion companion2 = ThermalStreamViewModel.INSTANCE;
                        Log.d(companion2.getTAG(), "handleConnectState: Connect returned '" + c2258a3 + '\'');
                        if (thermalStreamViewModel.isConnecting.get()) {
                        }
                    }
                }
            }
            if (i3 != 1) {
                if (i3 != 2) {
                    throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
                }
                AbstractC2176a.l(obj);
            }
            i = this.I$0;
            c2258a = (C2258a) this.L$2;
            thermalStreamViewModel = (ThermalStreamViewModel) this.L$1;
            AbstractC2176a.l(obj);
            C2258a c2258a32 = (C2258a) obj;
            Companion companion22 = ThermalStreamViewModel.INSTANCE;
            Log.d(companion22.getTAG(), "handleConnectState: Connect returned '" + c2258a32 + '\'');
            if (thermalStreamViewModel.isConnecting.get()) {
                Log.d(companion22.getTAG(), "Disconnect was called after connect → abort");
                thermalStreamViewModel.clear();
                return wVar;
            }
            if (c2258a32 == null) {
                if (thermalStreamViewModel.currentCamera != null) {
                    thermalStreamViewModel.currentCamera = null;
                    thermalStreamViewModel.connectToEmulator();
                } else if (thermalStreamViewModel.currentEmulator != null) {
                    thermalStreamViewModel.currentEmulator = null;
                }
                return wVar;
            }
            f fVar2 = P.f16067a;
            m9.e eVar2 = m9.e.f20045a;
            ThermalStreamViewModel$connectCamera$1$1$1 thermalStreamViewModel$connectCamera$1$1$1 = new ThermalStreamViewModel$connectCamera$1$1$1(thermalStreamViewModel, c2258a, null);
            this.L$0 = null;
            this.L$1 = null;
            this.L$2 = null;
            this.I$0 = i;
            this.label = 2;
            return F.L(eVar2, thermalStreamViewModel$connectCamera$1$1$1, this) == enumC2851a ? enumC2851a : wVar;
        }

        @Override // H7.n
        public final Object invoke(C c10, InterfaceC2738c<? super w> interfaceC2738c) {
            return ((C11371) create(c10, interfaceC2738c)).invokeSuspend(w.f23807a);
        }
    }

    @InterfaceC2888e(c = "com.flir.earhart.common.viewModel.ThermalStreamViewModel", f = "ThermalStreamViewModel.kt", l = {362, 269}, m = "disconnect")
    @Metadata(k = 3, mv = {2, 2, 0}, xi = 48)
    /* renamed from: com.flir.earhart.common.viewModel.ThermalStreamViewModel$disconnect$1, reason: invalid class name and case insensitive filesystem */
    public static final class C11381 extends AbstractC2886c {
        int I$0;
        int I$1;
        Object L$0;
        Object L$1;
        int label;
        /* synthetic */ Object result;

        public C11381(InterfaceC2738c<? super C11381> interfaceC2738c) {
            super(interfaceC2738c);
        }

        @Override // z7.AbstractC2884a
        public final Object invokeSuspend(Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return ThermalStreamViewModel.this.disconnect(null, this);
        }
    }

    @Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\n"}, d2 = {"<anonymous>", "", "Lkotlinx/coroutines/CoroutineScope;"}, k = 3, mv = {2, 2, 0}, xi = 48)
    @InterfaceC2888e(c = "com.flir.earhart.common.viewModel.ThermalStreamViewModel$recall$1", f = "ThermalStreamViewModel.kt", l = {}, m = "invokeSuspend")
    /* renamed from: com.flir.earhart.common.viewModel.ThermalStreamViewModel$recall$1, reason: invalid class name and case insensitive filesystem */
    public static final class C11391 extends i implements n {
        final /* synthetic */ String $fileUriString;
        final /* synthetic */ EnumC2411B $recallUriType;
        int label;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        public C11391(String str, EnumC2411B enumC2411B, InterfaceC2738c<? super C11391> interfaceC2738c) {
            super(2, interfaceC2738c);
            this.$fileUriString = str;
            this.$recallUriType = enumC2411B;
        }

        @Override // z7.AbstractC2884a
        public final InterfaceC2738c<w> create(Object obj, InterfaceC2738c<?> interfaceC2738c) {
            return ThermalStreamViewModel.this.new C11391(this.$fileUriString, this.$recallUriType, interfaceC2738c);
        }

        @Override // z7.AbstractC2884a
        public final Object invokeSuspend(Object obj) {
            EnumC2851a enumC2851a = EnumC2851a.COROUTINE_SUSPENDED;
            if (this.label != 0) {
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
            AbstractC2176a.l(obj);
            InterfaceC2417e interfaceC2417e = ThermalStreamViewModel.this.streamService;
            String fileUriString = this.$fileUriString;
            EnumC2411B recallUriType = this.$recallUriType;
            j0 j0Var = (j0) interfaceC2417e;
            synchronized (j0Var) {
                try {
                    l.e(fileUriString, "fileUriString");
                    l.e(recallUriType, "recallUriType");
                    EnumC2424l enumC2424lU = j0Var.u();
                    EnumC2424l enumC2424l = EnumC2424l.LIVE;
                    if (enumC2424lU == enumC2424l) {
                        ((C2720n) j0Var.f25697h).e("resource_tree/image_adjust/live_full_screen", String.valueOf(j0Var.v()));
                    }
                    if (j0Var.u() == enumC2424l) {
                        ((C2720n) j0Var.f25697h).e("resource_tree/image_adjust/adjust_mode", ((C2727v) j0Var.f25691e).g().name());
                    }
                    j0Var.f25657G.setValue(EnumC2424l.RECALL);
                    ((C2730y) j0Var.f25687c).f25797e = true;
                    try {
                        C0903t c0903tA = ((C1443b) j0Var.f25693f).a();
                        if (c0903tA != null) {
                            f fVar = P.f16067a;
                            F.B(c0903tA, m9.e.f20045a, null, new x4.Y(j0Var, null), 2);
                        }
                        C0903t c0903tA2 = ((C1443b) j0Var.f25693f).a();
                        if (c0903tA2 != null) {
                            f fVar2 = P.f16067a;
                            F.B(c0903tA2, m9.e.f20045a, null, new a0(j0Var, recallUriType, fileUriString, null), 2);
                        }
                    } catch (Exception e5) {
                        Log.d(j0.f25644N0, "setRecall() exception. " + e5.getMessage());
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
            return w.f23807a;
        }

        @Override // H7.n
        public final Object invoke(C c10, InterfaceC2738c<? super w> interfaceC2738c) {
            return ((C11391) create(c10, interfaceC2738c)).invokeSuspend(w.f23807a);
        }
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public ThermalStreamViewModel(InterfaceC2415c cameraScanService, InterfaceC2417e streamService, e tsdkEmulatorService, d mixPanelAnalyticsService, M tsdkImageAdjustSettingsService, V thermalImageAdjustService, b0 thermalPaletteService, Y thermalMeasurementService, K saveThermalService, CommonStatusService commonStatusService, CommonSettingsService commonSettingsService, Application application) {
        super(application);
        l.e(cameraScanService, "cameraScanService");
        l.e(streamService, "streamService");
        l.e(tsdkEmulatorService, "tsdkEmulatorService");
        l.e(mixPanelAnalyticsService, "mixPanelAnalyticsService");
        l.e(tsdkImageAdjustSettingsService, "tsdkImageAdjustSettingsService");
        l.e(thermalImageAdjustService, "thermalImageAdjustService");
        l.e(thermalPaletteService, "thermalPaletteService");
        l.e(thermalMeasurementService, "thermalMeasurementService");
        l.e(saveThermalService, "saveThermalService");
        l.e(commonStatusService, "commonStatusService");
        l.e(commonSettingsService, "commonSettingsService");
        l.e(application, "application");
        this.cameraScanService = cameraScanService;
        this.streamService = streamService;
        this.tsdkEmulatorService = tsdkEmulatorService;
        this.mixPanelAnalyticsService = mixPanelAnalyticsService;
        this.tsdkImageAdjustSettingsService = tsdkImageAdjustSettingsService;
        this.thermalImageAdjustService = thermalImageAdjustService;
        this.thermalPaletteService = thermalPaletteService;
        this.thermalMeasurementService = thermalMeasurementService;
        this.saveThermalService = saveThermalService;
        this.commonStatusService = commonStatusService;
        this.commonSettingsService = commonSettingsService;
        this.emulatorType = EmulatorType.EMULATOR_EARHART;
        this.currentConnectionType = EnumC2419g.FULL_STREAM;
        this.currentUseEmulatorTypeEarhart = true;
        this.reconnectionTimer$delegate = C0508c.u(null);
        this.useOpenGL$delegate = C0508c.u(Boolean.FALSE);
        this.imageBitmap$delegate = C0508c.u(null);
        this.scaleBitmap$delegate = C0508c.u(null);
        this.scaleHigh$delegate = new C0525k0(293.15f);
        this.scaleLow$delegate = new C0525k0(293.15f);
        this.longInitiationOfStream$delegate = C0508c.u(C0508c.o(new a(this, 0)));
        this.isConnecting = new AtomicBoolean(false);
        this.disconnectMutex = o9.d.a();
    }

    private final void cameraScan() {
        InterfaceC2415c interfaceC2415c = this.cameraScanService;
        List listL = ((C2723q) this.tsdkEmulatorService).a() ? h.l(c.EMULATOR) : h.l(c.EARHART);
        com.flir.earhart.common.service.e eVar = new com.flir.earhart.common.service.e(7);
        a aVar = new a(this, 1);
        a aVar2 = new a(this, 2);
        b bVar = new b(this, 2);
        C2716j c2716j = (C2716j) interfaceC2415c;
        c2716j.getClass();
        c2716j.f25641c = bVar;
        F.B(c2716j.f25639a, null, null, new C2715i(c2716j, listL, eVar, aVar, aVar2, null), 3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final w cameraScan$lambda$2() {
        Log.d(TAG, "Camera scan started.");
        return w.f23807a;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final w cameraScan$lambda$3(ThermalStreamViewModel thermalStreamViewModel) {
        if (thermalStreamViewModel.currentCamera != null) {
            thermalStreamViewModel.setUseOpenGL(true);
            thermalStreamViewModel.connectCamera();
        } else if (thermalStreamViewModel.currentEmulator != null) {
            thermalStreamViewModel.connectToEmulator();
        } else {
            Log.e(TAG, "onScanFinished: cannot connect to 'other' camera.");
        }
        return w.f23807a;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final boolean cameraScan$lambda$4(ThermalStreamViewModel thermalStreamViewModel) {
        return thermalStreamViewModel.currentCamera != null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final w cameraScan$lambda$5(ThermalStreamViewModel thermalStreamViewModel, C2258a camera) {
        l.e(camera, "camera");
        String string = camera.f22517b.toString();
        l.d(string, "toString(...)");
        if (c9.h.x0(string, "ACE camera", false) || c9.h.x0(string, "Earhart splitter camera", false) || c9.h.x0(string, "Earhart camera", false)) {
            thermalStreamViewModel.currentCamera = camera;
        } else if (c9.h.x0(string, "EMULATED EARHART", false) && thermalStreamViewModel.emulatorType == EmulatorType.EMULATOR_EARHART) {
            thermalStreamViewModel.currentEmulator = camera;
        } else if (c9.h.x0(string, "EMULATED FLIR", false) && thermalStreamViewModel.emulatorType == EmulatorType.EMULATOR_FLIR_ONE) {
            thermalStreamViewModel.currentEmulator = camera;
        } else {
            Log.d(TAG, "onCameraFound: found 'other' camera.");
        }
        return w.f23807a;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void clear() {
        setImageBitmap(null);
        this.currentCamera = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0017  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public final Object collectCameraProperties(InterfaceC2738c<? super C1194a> interfaceC2738c) throws Resources.NotFoundException {
        AnonymousClass1 anonymousClass1;
        String strName;
        String modeName;
        String currentTemperatureRangeString;
        Resources resources;
        String str;
        String str2;
        if (interfaceC2738c instanceof AnonymousClass1) {
            anonymousClass1 = (AnonymousClass1) interfaceC2738c;
            int i = anonymousClass1.label;
            if ((i & Integer.MIN_VALUE) != 0) {
                anonymousClass1.label = i - Integer.MIN_VALUE;
            } else {
                anonymousClass1 = new AnonymousClass1(interfaceC2738c);
            }
        }
        Object obj = anonymousClass1.result;
        EnumC2851a enumC2851a = EnumC2851a.COROUTINE_SUSPENDED;
        int i3 = anonymousClass1.label;
        if (i3 == 0) {
            AbstractC2176a.l(obj);
            String string = ((C2724s) this.tsdkImageAdjustSettingsService).c().toString();
            strName = ((EnumC2410A) ((D) this.thermalPaletteService).f23015g.getValue()).name();
            String strName2 = ((s4.w) this.thermalMeasurementService).f23099h.name();
            modeName = ((C2724s) this.tsdkImageAdjustSettingsService).b().getModeName();
            currentTemperatureRangeString = this.commonStatusService.getCurrentTemperatureRangeString();
            Resources resources2 = CommonStatusProvider.INSTANCE.getResources();
            CommonSettingsService commonSettingsService = this.commonSettingsService;
            anonymousClass1.L$0 = string;
            anonymousClass1.L$1 = strName;
            anonymousClass1.L$2 = strName2;
            anonymousClass1.L$3 = modeName;
            anonymousClass1.L$4 = currentTemperatureRangeString;
            anonymousClass1.L$5 = resources2;
            anonymousClass1.label = 1;
            Object activeTempUnit = commonSettingsService.getActiveTempUnit(anonymousClass1);
            if (activeTempUnit == enumC2851a) {
                return enumC2851a;
            }
            resources = resources2;
            str = strName2;
            str2 = string;
            obj = activeTempUnit;
        } else {
            if (i3 != 1) {
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
            resources = (Resources) anonymousClass1.L$5;
            currentTemperatureRangeString = (String) anonymousClass1.L$4;
            modeName = (String) anonymousClass1.L$3;
            String str3 = (String) anonymousClass1.L$2;
            strName = (String) anonymousClass1.L$1;
            String str4 = (String) anonymousClass1.L$0;
            AbstractC2176a.l(obj);
            str = str3;
            str2 = str4;
        }
        String str5 = modeName;
        String string2 = resources.getString(((NamedTempUnit) obj).getDisplaySymbol());
        l.d(string2, "getString(...)");
        return new C1194a(str2, strName, str, str5, c9.h.d1(c9.h.P0(currentTemperatureRangeString, string2)).toString(), ((C2340a) this.saveThermalService).a().getModeName(), ToolbarExtentionsKt.toFormattedDistance$default(((m) this.thermalImageAdjustService).f23058o.k(), false, 1, null), ToolbarExtentionsKt.toFormattedTemperature$default(((m) this.thermalImageAdjustService).f23054k.k(), false, false, 3, null), ToolbarExtentionsKt.toFormattedPercent$default(((m) this.thermalImageAdjustService).f23057n.k(), false, 1, (Object) null), ToolbarExtentionsKt.toFormattedTemperature$default(((m) this.thermalImageAdjustService).f23055l.k(), false, false, 3, null), ToolbarExtentionsKt.toFormattedEmissivity(((m) this.thermalImageAdjustService).f23053j.k()), String.format("%.1f", Arrays.copyOf(new Object[]{new Float(((C2724s) this.tsdkImageAdjustSettingsService).d())}, 1)), String.valueOf(((j0) this.streamService).v()), String.valueOf(!this.commonStatusService.getHideGraphics()));
    }

    private final void connectCamera() {
        F.B(T.j(this), null, null, new C11371(null), 3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void connectToEmulator() {
        if (this.currentEmulator != null) {
            setUseOpenGL(false);
            this.currentCamera = null;
            connectCamera();
        }
    }

    public static /* synthetic */ Object disconnect$default(ThermalStreamViewModel thermalStreamViewModel, H7.a aVar, InterfaceC2738c interfaceC2738c, int i, Object obj) {
        if ((i & 1) != 0) {
            aVar = null;
        }
        return thermalStreamViewModel.disconnect(aVar, interfaceC2738c);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final w disconnect$lambda$7$lambda$6(ThermalStreamViewModel thermalStreamViewModel, H7.a aVar) {
        thermalStreamViewModel.clear();
        if (aVar != null) {
            aVar.invoke();
        }
        return w.f23807a;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final TimerTask getReconnectionTimer() {
        return (TimerTask) this.reconnectionTimer$delegate.getValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final boolean longInitiationOfStream_delegate$lambda$0(ThermalStreamViewModel thermalStreamViewModel) {
        return thermalStreamViewModel.getReconnectionTimer() != null;
    }

    public static /* synthetic */ void recall$default(ThermalStreamViewModel thermalStreamViewModel, String str, EnumC2411B enumC2411B, int i, Object obj) {
        if ((i & 2) != 0) {
            enumC2411B = EnumC2411B.UNKNOWN;
        }
        thermalStreamViewModel.recall(str, enumC2411B);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void setReconnectionTimer(TimerTask timerTask) {
        this.reconnectionTimer$delegate.setValue(timerTask);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void setupStreamCallback() {
        InterfaceC2417e interfaceC2417e = this.streamService;
        b bVar = new b(this, 0);
        Q3.a aVar = new Q3.a(this, 6);
        b bVar2 = new b(this, 1);
        j0 j0Var = (j0) interfaceC2417e;
        j0Var.getClass();
        j0Var.f25677U = aVar;
        j0Var.f25678V = bVar2;
        j0Var.f25676T = bVar;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final w setupStreamCallback$lambda$10(ThermalStreamViewModel thermalStreamViewModel, Bitmap scaleBmp) {
        l.e(scaleBmp, "scaleBmp");
        thermalStreamViewModel.setScaleBitmap(new C1641d(scaleBmp));
        return w.f23807a;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final w setupStreamCallback$lambda$8(ThermalStreamViewModel thermalStreamViewModel, Bitmap bitmap) {
        Stream stream = ((j0) thermalStreamViewModel.streamService).f25704l;
        thermalStreamViewModel.setImageBitmap((((stream == null || !stream.isStreaming()) && ((j0) thermalStreamViewModel.streamService).u() == EnumC2424l.LIVE) || bitmap == null) ? null : new C1641d(bitmap));
        return w.f23807a;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final w setupStreamCallback$lambda$9(ThermalStreamViewModel thermalStreamViewModel, float f10, float f11) {
        thermalStreamViewModel.setScaleHigh(f10);
        thermalStreamViewModel.setScaleLow(f11);
        return w.f23807a;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final synchronized void startConnectionProcess() {
        try {
            String str = TAG;
            Log.d(str, "Start connection process.");
            if (((j0) this.streamService).f25706m != null) {
                Log.d(str, "Cleaning up old connection");
                ((j0) this.streamService).h();
            }
            if (((C2723q) this.tsdkEmulatorService).a()) {
                this.emulatorType = this.currentUseEmulatorTypeEarhart ? EmulatorType.EMULATOR_EARHART : EmulatorType.EMULATOR_FLIR_ONE;
            } else if (this.currentConnectionType == EnumC2419g.FULL_STREAM) {
                ((j0) this.streamService).J(getApplication());
            }
            setImageBitmap(null);
            cameraScan();
        } catch (Throwable th) {
            throw th;
        }
    }

    private final long timeSinceLastDisconnection() {
        long epochMilli = Instant.now().toEpochMilli();
        String strC = ((C2720n) ((j0) this.streamService).f25697h).c("resource_tree/last_stream_disconnect");
        long j10 = epochMilli - (strC != null ? Long.parseLong(strC) : 0L);
        if (j10 < 0) {
            return 2001L;
        }
        return j10;
    }

    public final synchronized void connect(EnumC2419g connectionType, boolean z4) {
        try {
            l.e(connectionType, "connectionType");
            this.isConnecting.set(true);
            this.currentConnectionType = connectionType;
            this.currentUseEmulatorTypeEarhart = z4;
            long jTimeSinceLastDisconnection = timeSinceLastDisconnection();
            if (jTimeSinceLastDisconnection >= 2000) {
                Log.d(TAG, "ThermalStreamViewModel.connect(): Connect immediately");
                startConnectionProcess();
                return;
            }
            TimerTask reconnectionTimer = getReconnectionTimer();
            if (reconnectionTimer != null) {
                reconnectionTimer.cancel();
            }
            Timer timer = new Timer("reconnectionTimer");
            long j10 = 2000 - jTimeSinceLastDisconnection;
            TimerTask timerTask = new TimerTask() { // from class: com.flir.earhart.common.viewModel.ThermalStreamViewModel$connect$$inlined$schedule$1
                @Override // java.util.TimerTask, java.lang.Runnable
                public void run() {
                    Log.d(ThermalStreamViewModel.TAG, "ThermalStreamViewModel.connect(): Delayed connect timeout => connect");
                    TimerTask reconnectionTimer2 = this.this$0.getReconnectionTimer();
                    if (reconnectionTimer2 != null) {
                        reconnectionTimer2.cancel();
                    }
                    this.this$0.setReconnectionTimer(null);
                    this.this$0.startConnectionProcess();
                }
            };
            timer.schedule(timerTask, j10, j10);
            setReconnectionTimer(timerTask);
        } catch (Throwable th) {
            throw th;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:7:0x0013  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public final Object disconnect(H7.a aVar, InterfaceC2738c<? super w> interfaceC2738c) throws Throwable {
        C11381 c11381;
        InterfaceC2087a interfaceC2087a;
        int i;
        Throwable th;
        InterfaceC2087a interfaceC2087a2;
        if (interfaceC2738c instanceof C11381) {
            c11381 = (C11381) interfaceC2738c;
            int i3 = c11381.label;
            if ((i3 & Integer.MIN_VALUE) != 0) {
                c11381.label = i3 - Integer.MIN_VALUE;
            } else {
                c11381 = new C11381(interfaceC2738c);
            }
        }
        Object obj = c11381.result;
        EnumC2851a enumC2851a = EnumC2851a.COROUTINE_SUSPENDED;
        int i6 = c11381.label;
        try {
            if (i6 == 0) {
                AbstractC2176a.l(obj);
                interfaceC2087a = this.disconnectMutex;
                c11381.L$0 = aVar;
                c11381.L$1 = interfaceC2087a;
                c11381.I$0 = 0;
                c11381.label = 1;
                if (interfaceC2087a.b(c11381) != enumC2851a) {
                    i = 0;
                }
                return enumC2851a;
            }
            if (i6 != 1) {
                if (i6 != 2) {
                    throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
                }
                interfaceC2087a2 = (InterfaceC2087a) c11381.L$1;
                try {
                    AbstractC2176a.l(obj);
                    w wVar = w.f23807a;
                    interfaceC2087a2.a(null);
                    return wVar;
                } catch (Throwable th2) {
                    th = th2;
                    interfaceC2087a = interfaceC2087a2;
                    interfaceC2087a.a(null);
                    throw th;
                }
            }
            int i10 = c11381.I$0;
            InterfaceC2087a interfaceC2087a3 = (InterfaceC2087a) c11381.L$1;
            H7.a aVar2 = (H7.a) c11381.L$0;
            AbstractC2176a.l(obj);
            interfaceC2087a = interfaceC2087a3;
            i = i10;
            aVar = aVar2;
            Log.d(TAG, "Disconnecting camera.");
            this.isConnecting.set(false);
            TimerTask reconnectionTimer = getReconnectionTimer();
            if (reconnectionTimer != null) {
                reconnectionTimer.cancel();
            }
            setReconnectionTimer(null);
            if (((j0) this.streamService).u() == EnumC2424l.LIVE) {
                InterfaceC2417e interfaceC2417e = this.streamService;
                long epochMilli = Instant.now().toEpochMilli();
                j0 j0Var = (j0) interfaceC2417e;
                j0Var.getClass();
                ((C2720n) j0Var.f25697h).e("resource_tree/last_stream_disconnect", String.valueOf(epochMilli));
            }
            InterfaceC2417e interfaceC2417e2 = this.streamService;
            C1032q c1032q = new C1032q(5, this, aVar);
            c11381.L$0 = null;
            c11381.L$1 = interfaceC2087a;
            c11381.I$0 = i;
            c11381.I$1 = 0;
            c11381.label = 2;
            if (((j0) interfaceC2417e2).N(c1032q, c11381) != enumC2851a) {
                interfaceC2087a2 = interfaceC2087a;
                w wVar2 = w.f23807a;
                interfaceC2087a2.a(null);
                return wVar2;
            }
            return enumC2851a;
        } catch (Throwable th3) {
            th = th3;
            interfaceC2087a.a(null);
            throw th;
        }
    }

    public final InterfaceC1627A getImageBitmap() {
        return (InterfaceC1627A) this.imageBitmap$delegate.getValue();
    }

    public final g1 getLongInitiationOfStream() {
        return (g1) this.longInitiationOfStream$delegate.getValue();
    }

    public final InterfaceC1627A getScaleBitmap() {
        return (InterfaceC1627A) this.scaleBitmap$delegate.getValue();
    }

    public final float getScaleHigh() {
        return ((C0525k0) this.scaleHigh$delegate).k();
    }

    public final float getScaleLow() {
        return ((C0525k0) this.scaleLow$delegate).k();
    }

    public final boolean getUseOpenGL() {
        return ((Boolean) this.useOpenGL$delegate.getValue()).booleanValue();
    }

    public final void recall(String fileUriString, EnumC2411B recallUriType) {
        l.e(fileUriString, "fileUriString");
        l.e(recallUriType, "recallUriType");
        setupStreamCallback();
        V1.a aVarJ = T.j(this);
        f fVar = P.f16067a;
        F.B(aVarJ, k9.m.f19034a, null, new C11391(fileUriString, recallUriType, null), 2);
    }

    public final void setImageBitmap(InterfaceC1627A interfaceC1627A) {
        this.imageBitmap$delegate.setValue(interfaceC1627A);
    }

    public final void setLongInitiationOfStream(g1 g1Var) {
        l.e(g1Var, "<set-?>");
        this.longInitiationOfStream$delegate.setValue(g1Var);
    }

    public final void setScaleBitmap(InterfaceC1627A interfaceC1627A) {
        this.scaleBitmap$delegate.setValue(interfaceC1627A);
    }

    public final void setScaleHigh(float f10) {
        ((C0525k0) this.scaleHigh$delegate).l(f10);
    }

    public final void setScaleLow(float f10) {
        ((C0525k0) this.scaleLow$delegate).l(f10);
    }

    public final void setUseOpenGL(boolean z4) {
        this.useOpenGL$delegate.setValue(Boolean.valueOf(z4));
    }
}
