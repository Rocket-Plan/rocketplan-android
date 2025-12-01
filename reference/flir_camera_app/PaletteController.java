package s4;

import Q.C0508c;
import Q.C0525k0;
import Q.C0533o0;
import com.flir.thermalsdk.image.IsothermType;
import com.flir.thermalsdk.image.Palette;
import com.flir.thermalsdk.image.PaletteManager;
import i9.T;
import java.util.Iterator;
import java.util.List;
import t4.C2437z;
import t4.EnumC2410A;
import t4.EnumC2424l;
import t4.EnumC2429q;
import t4.InterfaceC2417e;
import t4.O;
import t4.b0;
import t4.c0;
import x4.C2705E;
import x4.C2718l;
import x4.C2720n;
import x4.G;
import x4.j0;

/* loaded from: classes.dex */
public final class D implements b0 {

    /* renamed from: a, reason: collision with root package name */
    public final c0 f23009a;

    /* renamed from: b, reason: collision with root package name */
    public final O f23010b;

    /* renamed from: c, reason: collision with root package name */
    public final h4.a f23011c;

    /* renamed from: d, reason: collision with root package name */
    public final T f23012d;

    /* renamed from: e, reason: collision with root package name */
    public final T f23013e;

    /* renamed from: f, reason: collision with root package name */
    public EnumC2424l f23014f = EnumC2424l.LIVE;

    /* renamed from: g, reason: collision with root package name */
    public final C0533o0 f23015g = C0508c.u(EnumC2410A.Iron);

    /* renamed from: h, reason: collision with root package name */
    public final C0533o0 f23016h = C0508c.u(Boolean.FALSE);
    public final C0533o0 i = C0508c.u(EnumC2429q.Inactive);

    /* renamed from: j, reason: collision with root package name */
    public final C0525k0 f23017j = new C0525k0(293.15f);

    /* renamed from: k, reason: collision with root package name */
    public final C0525k0 f23018k = new C0525k0(293.15f);

    /* renamed from: l, reason: collision with root package name */
    public final C0525k0 f23019l = new C0525k0(293.15f);

    /* renamed from: m, reason: collision with root package name */
    public final C0525k0 f23020m = new C0525k0(283.15f);

    public D(c0 c0Var, O o6, InterfaceC2417e interfaceC2417e, h4.a aVar) {
        this.f23009a = c0Var;
        this.f23010b = o6;
        this.f23011c = aVar;
        j0 j0Var = (j0) interfaceC2417e;
        this.f23012d = j0Var.f25729x0;
        this.f23013e = j0Var.f25731y0;
    }

    public final float a() {
        int i = x.f23105a[b().ordinal()];
        C0525k0 c0525k0 = this.f23017j;
        return i != 1 ? i != 2 ? i != 3 ? c0525k0.k() : this.f23019l.k() : this.f23018k.k() : c0525k0.k();
    }

    public final EnumC2429q b() {
        return (EnumC2429q) this.i.getValue();
    }

    public final void c() {
        Object next;
        C2437z c2437z;
        String strC = ((C2720n) ((C2705E) this.f23010b).f25522a).c("resource_tree/isotherm/type");
        if (strC == null) {
            strC = "";
        }
        EnumC2429q.Companion.getClass();
        Iterator<E> it = EnumC2429q.getEntries().iterator();
        while (true) {
            if (it.hasNext()) {
                next = it.next();
                if (kotlin.jvm.internal.l.a(((EnumC2429q) next).getIsoTypeName(), strC)) {
                    break;
                }
            } else {
                next = null;
                break;
            }
        }
        EnumC2429q enumC2429q = (EnumC2429q) next;
        if (enumC2429q == null) {
            enumC2429q = EnumC2429q.Inactive;
        }
        f(enumC2429q);
        String strC2 = ((C2720n) ((C2705E) this.f23010b).f25522a).c("resource_tree/isotherm/aboveThresholdT");
        d(strC2 != null ? Float.parseFloat(strC2) : 293.15f);
        String strC3 = ((C2720n) ((C2705E) this.f23010b).f25522a).c("resource_tree/isotherm/belowThresholdT");
        e(strC3 != null ? Float.parseFloat(strC3) : 293.15f);
        String strC4 = ((C2720n) ((C2705E) this.f23010b).f25522a).c("resource_tree/isotherm/intervalHighT");
        this.f23019l.l(strC4 != null ? Float.parseFloat(strC4) : 293.15f);
        EnumC2424l enumC2424l = this.f23014f;
        EnumC2424l enumC2424l2 = EnumC2424l.LIVE;
        if (enumC2424l == enumC2424l2) {
            ((C2720n) ((C2705E) this.f23010b).f25522a).e("resource_tree/isotherm/intervalHighT", String.valueOf(this.f23019l.k()));
        }
        float fK = this.f23019l.k();
        C2718l c2718l = ((G) this.f23009a).f25526a;
        c2718l.f25751k = fK;
        c2718l.f25752l = true;
        String strC5 = ((C2720n) ((C2705E) this.f23010b).f25522a).c("resource_tree/isotherm/intervalLowT");
        this.f23020m.l(strC5 != null ? Float.parseFloat(strC5) : 283.15f);
        if (this.f23014f == enumC2424l2) {
            ((C2720n) ((C2705E) this.f23010b).f25522a).e("resource_tree/isotherm/intervalLowT", String.valueOf(this.f23020m.k()));
        }
        float fK2 = this.f23020m.k();
        C2718l c2718l2 = ((G) this.f23009a).f25526a;
        c2718l2.f25753m = fK2;
        c2718l2.f25754n = true;
        C2705E c2705e = (C2705E) this.f23010b;
        synchronized (c2705e) {
            try {
                List<Palette> defaultPalettes = PaletteManager.getDefaultPalettes();
                kotlin.jvm.internal.l.b(defaultPalettes);
                int i = 0;
                for (Palette palette : defaultPalettes) {
                    C2437z c2437z2 = EnumC2410A.Companion;
                    String name = palette.name;
                    kotlin.jvm.internal.l.d(name, "name");
                    c2437z2.getClass();
                    EnumC2410A enumC2410AA = C2437z.a(name);
                    if (enumC2410AA != EnumC2410A.Ignored) {
                        c2705e.f25523b.add(new t7.k(enumC2410AA, Integer.valueOf(i)));
                    }
                    i++;
                }
                String strC6 = ((C2720n) c2705e.f25522a).c("resource_tree/palette/current");
                if (strC6 == null) {
                    strC6 = "";
                }
                c2437z = EnumC2410A.Companion;
                c2437z.getClass();
                EnumC2410A palette2 = C2437z.a(strC6);
                kotlin.jvm.internal.l.e(palette2, "palette");
                ((C2720n) c2705e.f25522a).e("resource_tree/palette/current", palette2.getPaletteName());
            } catch (Throwable th) {
                throw th;
            }
        }
        String strC7 = ((C2720n) ((C2705E) this.f23010b).f25522a).c("resource_tree/palette/current");
        if (strC7 == null) {
            strC7 = "";
        }
        c2437z.getClass();
        g(C2437z.a(strC7));
        String strC8 = ((C2720n) ((C2705E) this.f23010b).f25522a).c("resource_tree/palette/reversed");
        i(strC8 != null ? Boolean.parseBoolean(strC8) : false);
    }

    public final void d(float f10) {
        C0525k0 c0525k0 = this.f23017j;
        c0525k0.l(f10);
        if (this.f23014f == EnumC2424l.LIVE) {
            float fK = c0525k0.k();
            C2705E c2705e = (C2705E) this.f23010b;
            ((C2720n) c2705e.f25522a).e("resource_tree/isotherm/aboveThresholdT", String.valueOf(fK));
        }
        float fK2 = c0525k0.k();
        C2718l c2718l = ((G) this.f23009a).f25526a;
        c2718l.f25748g = fK2;
        c2718l.f25749h = true;
    }

    public final void e(float f10) {
        C0525k0 c0525k0 = this.f23018k;
        c0525k0.l(f10);
        if (this.f23014f == EnumC2424l.LIVE) {
            float fK = c0525k0.k();
            C2705E c2705e = (C2705E) this.f23010b;
            ((C2720n) c2705e.f25522a).e("resource_tree/isotherm/belowThresholdT", String.valueOf(fK));
        }
        float fK2 = c0525k0.k();
        C2718l c2718l = ((G) this.f23009a).f25526a;
        c2718l.i = fK2;
        c2718l.f25750j = true;
    }

    public final void f(EnumC2429q value) {
        kotlin.jvm.internal.l.e(value, "value");
        this.i.setValue(value);
        if (this.f23014f == EnumC2424l.LIVE) {
            EnumC2429q isoType = b();
            C2705E c2705e = (C2705E) this.f23010b;
            kotlin.jvm.internal.l.e(isoType, "isoType");
            ((C2720n) c2705e.f25522a).e("resource_tree/isotherm/type", isoType.getIsoTypeName());
        }
        EnumC2429q isoType2 = b();
        G g10 = (G) this.f23009a;
        kotlin.jvm.internal.l.e(isoType2, "isoType");
        C2718l c2718l = g10.f25526a;
        c2718l.getClass();
        c2718l.f25745d = isoType2;
        c2718l.f25747f = true;
        if (isoType2 == EnumC2429q.Above) {
            IsothermType isothermType = IsothermType.ABOVE;
            kotlin.jvm.internal.l.e(isothermType, "<set-?>");
            c2718l.f25746e = isothermType;
        } else if (isoType2 == EnumC2429q.Below) {
            IsothermType isothermType2 = IsothermType.BELOW;
            kotlin.jvm.internal.l.e(isothermType2, "<set-?>");
            c2718l.f25746e = isothermType2;
        } else if (isoType2 == EnumC2429q.Interval) {
            IsothermType isothermType3 = IsothermType.INTERVAL;
            kotlin.jvm.internal.l.e(isothermType3, "<set-?>");
            c2718l.f25746e = isothermType3;
        }
    }

    public final void g(EnumC2410A palette) {
        Object next;
        int iIntValue;
        kotlin.jvm.internal.l.e(palette, "palette");
        if (this.f23014f == EnumC2424l.LIVE) {
            ((C2720n) ((C2705E) this.f23010b).f25522a).e("resource_tree/palette/current", palette.getPaletteName());
        }
        this.f23015g.setValue(palette);
        C2705E c2705e = (C2705E) this.f23010b;
        synchronized (c2705e) {
            try {
                Iterator it = c2705e.f25523b.iterator();
                while (true) {
                    if (!it.hasNext()) {
                        next = null;
                        break;
                    } else {
                        next = it.next();
                        if (((t7.k) next).f23789a == palette) {
                            break;
                        }
                    }
                }
                t7.k kVar = (t7.k) next;
                iIntValue = kVar != null ? ((Number) kVar.f23790b).intValue() : -1;
            } catch (Throwable th) {
                throw th;
            }
        }
        G g10 = (G) this.f23009a;
        if (iIntValue < 0 || iIntValue >= PaletteManager.getDefaultPalettes().size()) {
            return;
        }
        C2718l c2718l = g10.f25526a;
        c2718l.f25742a = iIntValue;
        c2718l.f25744c = true;
    }

    public final void h(EnumC2410A palette, boolean z4, EnumC2429q isoType) {
        kotlin.jvm.internal.l.e(palette, "palette");
        kotlin.jvm.internal.l.e(isoType, "isoType");
        g(palette);
        i(z4);
        f(isoType);
    }

    public final void i(boolean z4) {
        if (this.f23014f == EnumC2424l.LIVE) {
            C2705E c2705e = (C2705E) this.f23010b;
            ((C2720n) c2705e.f25522a).e("resource_tree/palette/reversed", String.valueOf(z4));
        }
        this.f23016h.setValue(Boolean.valueOf(z4));
        C2718l c2718l = ((G) this.f23009a).f25526a;
        c2718l.f25743b = z4;
        c2718l.f25744c = true;
    }
}
