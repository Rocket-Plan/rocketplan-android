package com.flir.thermalsdk.live;

import com.flir.thermalsdk.image.JavaImageBuffer;
import com.flir.thermalsdk.image.Palette;
import com.flir.thermalsdk.image.Rectangle;
import com.flir.thermalsdk.image.RotationAngle;
import com.flir.thermalsdk.image.TemperatureUnit;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.image.ThermalValue;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.connectivity.ConnectorFactory;
import com.flir.thermalsdk.live.importing.Importer;
import com.flir.thermalsdk.live.importing.ImporterFactory;
import com.flir.thermalsdk.live.remote.RemoteControl;
import com.flir.thermalsdk.live.streaming.Stream;
import com.flir.thermalsdk.live.streaming.ThermalImageStreamListener;
import com.flir.thermalsdk.log.ThermalLog;
import com.flir.thermalsdk.utils.Pair;
import java.io.File;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/* loaded from: classes.dex */
public final class Camera implements AutoCloseable {
    private static final String TAG = "Camera";
    private final File mAuthStorage;
    private long mNativeInstance;
    private InternalAuthenticationResponse mInternalAuthResponse = new InternalAuthenticationResponse();
    private long mThermalImageStreamAddr = 0;
    private long glFrameRendererAddr = 0;
    private final BroadcastingStreamListener mStreamListeners = new BroadcastingStreamListener(0);

    public static class BroadcastingStreamListener implements ThermalImageStreamListener {
        private final Set<ThermalImageStreamListener> listeners;

        public /* synthetic */ BroadcastingStreamListener(int i) {
            this();
        }

        public synchronized void addListener(ThermalImageStreamListener thermalImageStreamListener) {
            this.listeners.add(thermalImageStreamListener);
        }

        public synchronized void clear() {
            this.listeners.clear();
        }

        public synchronized boolean isEmpty() {
            return this.listeners.isEmpty();
        }

        @Override // com.flir.thermalsdk.live.streaming.ThermalImageStreamListener
        public synchronized void onImageReceived() {
            Iterator<ThermalImageStreamListener> it = this.listeners.iterator();
            while (it.hasNext()) {
                it.next().onImageReceived();
            }
        }

        public synchronized void removeListener(ThermalImageStreamListener thermalImageStreamListener) {
            this.listeners.remove(thermalImageStreamListener);
        }

        private BroadcastingStreamListener() {
            this.listeners = new HashSet();
        }
    }

    @Deprecated
    public interface Consumer<T> extends com.flir.thermalsdk.utils.Consumer<T> {
    }

    public Camera() {
        this.mNativeInstance = 0L;
        ConnectorFactory.getInstance();
        this.mNativeInstance = nativeCreate();
        this.mAuthStorage = AuthenticationFileStorage.getStorage();
    }

    private void assertValidNativeCamera(String str) {
        if (this.mNativeInstance == 0) {
            throw new IllegalStateException(str);
        }
    }

    private void assertValidNativeGLFrameRenderer(String str) {
        if (this.glFrameRendererAddr == 0) {
            throw new IllegalStateException(str);
        }
    }

    private void assertValidNativeStream(String str) {
        if (this.mThermalImageStreamAddr == 0) {
            throw new IllegalStateException(str);
        }
    }

    private byte[] getUsageData() {
        assertValidNativeCamera("Native instance pointer is null when calling getUsageData()");
        return nativeGetUsageData(this.mNativeInstance);
    }

    private static native void glEnableMsxNative(long j10, boolean z4);

    private static native Rectangle glGetRegionOfInterestNative(long j10);

    private static native ThermalValue[] glGetScaleRangeNative(long j10);

    private static native boolean glOnDrawFrameNative(long j10);

    private static native void glOnSurfaceChangedNative(long j10, int i, int i3, float f10);

    private static native JavaImageBuffer glRenderScaleNative(long j10);

    private static native void glScaleAutoAdjustNative(long j10, boolean z4);

    private static native void glSetPaletteNative(long j10, Palette palette);

    private static native void glSetRegionOfInterestNative(long j10, Rectangle rectangle);

    private static native void glSetViewportNative(long j10, int i, int i3, int i6, int i10, float f10);

    private static native long glSetupPipelineNative(long j10, boolean z4);

    private static native void glTeardownPipelineNative(long j10);

    private static native void glWithThermalImageNative(long j10, com.flir.thermalsdk.utils.Consumer<ThermalImage> consumer);

    private static native InternalAuthenticationResponse nativeAuthenticate(long j10, Identity identity, String str, String str2, String str3, long j11);

    private static native void nativeConnect(long j10, long j11, Identity identity, String str, ConnectionStatusListener connectionStatusListener, ConnectParameters connectParameters);

    private static native long nativeCreate();

    private static native void nativeDelete(long j10, long j11);

    private static native void nativeDisconnect(long j10);

    private native Exporter nativeGetExporter();

    private static native Identity nativeGetIdentity(long j10);

    private static native byte[] nativeGetLicenseData(long j10);

    private native RemoteControl nativeGetRemoteControl();

    private static native Stream[] nativeGetStreams(long j10);

    private static native byte[] nativeGetUsageData(long j10);

    private static native Identity nativeIpAddressToIdentity(String str);

    private static native boolean nativeIsConnected(long j10);

    private static native boolean nativeIsStreaming(long j10);

    private static native void nativeRequestHighResolutionSnapshot(long j10, String str);

    private static native void nativeSetDisplayProperties(long j10, RotationAngle rotationAngle, float f10, float f11, float f12, float f13);

    private static native void nativeSetRegion(long j10, String str, boolean z4, boolean z10);

    private static native long nativeStartStream(long j10, ThermalImageStreamListener thermalImageStreamListener);

    private static native void nativeStopStream(long j10, long j11);

    private static native void nativeToggleLamp(long j10, boolean z4);

    private static native void nativeWithImage(long j10, com.flir.thermalsdk.utils.Consumer<ThermalImage> consumer);

    private static void throwIfNull(Object obj, String str) {
        if (obj == null) {
            throw new IllegalArgumentException(str);
        }
    }

    public synchronized AuthenticationResponse authenticate(Identity identity, String str, long j10) {
        InternalAuthenticationResponse internalAuthenticationResponseNativeAuthenticate;
        throwIfNull(identity, "Not allowed to call Camera#authenticate(..) with null Identity");
        assertValidNativeCamera("Native instance pointer is null when calling authenticate()");
        internalAuthenticationResponseNativeAuthenticate = nativeAuthenticate(this.mInternalAuthResponse.authenticationResponseId, identity, this.mAuthStorage.getAbsolutePath(), "java-auth-base", str, j10);
        this.mInternalAuthResponse = internalAuthenticationResponseNativeAuthenticate;
        return internalAuthenticationResponseNativeAuthenticate;
    }

    @Override // java.lang.AutoCloseable
    public synchronized void close() {
        long j10 = this.mNativeInstance;
        if (j10 != 0) {
            nativeDelete(j10, this.mInternalAuthResponse.authenticationResponseId);
            this.mNativeInstance = 0L;
            this.mInternalAuthResponse = null;
        }
    }

    public synchronized void connect(InetAddress inetAddress, ConnectionStatusListener connectionStatusListener, ConnectParameters connectParameters) {
        throwIfNull(inetAddress, "Not allowed to call Camera#connect(..) with null InetAddress");
        throwIfNull(inetAddress.getHostAddress(), "Not allowed to call Camera#connect(..) with null InetAddress.getHostAddress()");
        assertValidNativeCamera("Native instance pointer is null when calling connect()");
        ThermalLog.d(TAG, "connect with ip:" + inetAddress.getHostAddress());
        connect(nativeIpAddressToIdentity(inetAddress.getHostAddress()), connectionStatusListener, connectParameters);
    }

    public synchronized void disconnect() {
        try {
            assertValidNativeCamera("Native instance pointer is null when calling disconnect()");
            ThermalLog.d(TAG, "disconnect, is connected: " + isConnected());
            if (isGrabbing()) {
                unsubscribeAllStreams();
            }
            nativeDisconnect(this.mNativeInstance);
        } catch (Throwable th) {
            throw th;
        }
    }

    public void finalize() {
        try {
            long j10 = this.mNativeInstance;
            if (j10 != 0) {
                nativeDelete(j10, this.mInternalAuthResponse.authenticationResponseId);
                this.mNativeInstance = 0L;
                this.mInternalAuthResponse = null;
            }
        } catch (Exception e5) {
            ThermalLog.w(TAG, "Got Camera.finalize() exception " + e5.getClass().getName() + ", msg: " + e5.getMessage());
        }
    }

    public synchronized Exporter getExporter() {
        assertValidNativeCamera("Native instance pointer is null when calling getExporter()");
        return nativeGetExporter();
    }

    public synchronized Identity getIdentity() {
        assertValidNativeCamera("Native instance pointer is null when calling getIdentity()");
        return nativeGetIdentity(this.mNativeInstance);
    }

    public synchronized Importer getImporter() {
        assertValidNativeCamera("Native instance pointer is null when calling getImporter()");
        return ImporterFactory.getInstance().create(this);
    }

    public byte[] getLicenseData() {
        assertValidNativeCamera("Native instance pointer is null when calling getLicenseData()");
        return nativeGetLicenseData(this.mNativeInstance);
    }

    public synchronized RemoteControl getRemoteControl() {
        assertValidNativeCamera("Native instance pointer is null when calling getRemoteControl()");
        return nativeGetRemoteControl();
    }

    public List<Stream> getStreams() {
        Stream[] streamArrNativeGetStreams = nativeGetStreams(this.mNativeInstance);
        return streamArrNativeGetStreams == null ? new ArrayList() : Arrays.asList(streamArrNativeGetStreams);
    }

    public void glEnableMsx(boolean z4) {
        long j10 = this.glFrameRendererAddr;
        if (j10 == 0) {
            ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glEnableMsxNative()");
        } else {
            glEnableMsxNative(j10, z4);
        }
    }

    public Rectangle glGetRegionOfInterest() {
        long j10 = this.glFrameRendererAddr;
        if (j10 != 0) {
            return glGetRegionOfInterestNative(j10);
        }
        ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glGetRegionOfInterest()");
        return new Rectangle(0, 0, 0, 0);
    }

    public Pair<ThermalValue, ThermalValue> glGetScaleRange() {
        long j10 = this.glFrameRendererAddr;
        if (j10 == 0) {
            ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glGetScaleRange()");
            TemperatureUnit temperatureUnit = TemperatureUnit.KELVIN;
            return new Pair<>(new ThermalValue(0.0d, temperatureUnit), new ThermalValue(0.0d, temperatureUnit));
        }
        ThermalValue[] thermalValueArrGlGetScaleRangeNative = glGetScaleRangeNative(j10);
        if (thermalValueArrGlGetScaleRangeNative != null) {
            return new Pair<>(thermalValueArrGlGetScaleRangeNative[0], thermalValueArrGlGetScaleRangeNative[1]);
        }
        TemperatureUnit temperatureUnit2 = TemperatureUnit.KELVIN;
        return new Pair<>(new ThermalValue(0.0d, temperatureUnit2), new ThermalValue(0.0d, temperatureUnit2));
    }

    public boolean glIsGlContextReady() {
        return this.glFrameRendererAddr != 0;
    }

    public boolean glOnDrawFrame() {
        long j10 = this.glFrameRendererAddr;
        if (j10 != 0) {
            return glOnDrawFrameNative(j10);
        }
        ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glOnDrawFrame()");
        return false;
    }

    public void glOnSurfaceChanged(int i, int i3) {
        glOnSurfaceChanged(i, i3, 0.0f);
    }

    public JavaImageBuffer glRenderScale() {
        long j10 = this.glFrameRendererAddr;
        if (j10 != 0) {
            return glRenderScaleNative(j10);
        }
        ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glRenderScale()");
        return null;
    }

    public void glScaleAutoAdjust(boolean z4) {
        long j10 = this.glFrameRendererAddr;
        if (j10 == 0) {
            ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glScaleAutoAdjust()");
        } else {
            glScaleAutoAdjustNative(j10, z4);
        }
    }

    public void glSetPalette(Palette palette) {
        long j10 = this.glFrameRendererAddr;
        if (j10 == 0) {
            ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glSetPalette()");
        } else {
            glSetPaletteNative(j10, palette);
        }
    }

    public void glSetRegionOfInterest(Rectangle rectangle) {
        long j10 = this.glFrameRendererAddr;
        if (j10 == 0) {
            ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glSetRegionOfInterest()");
        } else {
            glSetRegionOfInterestNative(j10, rectangle);
        }
    }

    public void glSetViewport(int i, int i3, int i6, int i10) {
        glSetViewport(i, i3, i6, i10, 0.0f);
    }

    public void glSetupPipeline(Stream stream, boolean z4) throws NoSuchFieldException, SecurityException {
        try {
            Field declaredField = Stream.class.getDeclaredField("cppStreamPtr");
            declaredField.setAccessible(true);
            this.glFrameRendererAddr = glSetupPipelineNative(((Long) declaredField.get(stream)).longValue(), z4);
        } catch (IllegalAccessException | NoSuchFieldException unused) {
        }
    }

    public void glTeardownPipeline() throws InterruptedException {
        if (this.glFrameRendererAddr != 0) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException unused) {
            }
            glTeardownPipelineNative(this.glFrameRendererAddr);
            this.glFrameRendererAddr = 0L;
        }
    }

    public void glWithThermalImage(com.flir.thermalsdk.utils.Consumer<ThermalImage> consumer) {
        long j10 = this.glFrameRendererAddr;
        if (j10 == 0) {
            ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glWithThermalImage()");
        } else {
            glWithThermalImageNative(j10, consumer);
        }
    }

    public synchronized boolean isConnected() {
        assertValidNativeCamera("Native instance pointer is null when calling isConnected()");
        return nativeIsConnected(this.mNativeInstance);
    }

    @Deprecated
    public synchronized boolean isGrabbing() {
        long j10;
        j10 = this.mNativeInstance;
        if (j10 == 0) {
            throw new NullPointerException("Native instance pointer is null when calling isGrabbing()");
        }
        return nativeIsStreaming(j10);
    }

    public void requestHighResolutionSnapshotToFile(String str) {
        assertValidNativeCamera("Native instance pointer is null when calling requestHighResolutionSnapshot()");
        nativeRequestHighResolutionSnapshot(this.mNativeInstance, str);
        ThermalLog.d(TAG, "requestHighResolutionSnapshot, successfully saved file: " + str);
    }

    public void setDisplayProperties(RotationAngle rotationAngle, float f10, float f11, float f12, float f13) {
        assertValidNativeCamera("Native instance pointer is null when calling setDisplayProperties()");
        nativeSetDisplayProperties(this.mNativeInstance, rotationAngle, f10, f11, f12, f13);
    }

    public synchronized void setRegion(String str, boolean z4, boolean z10) {
        ThermalLog.d(TAG, "setRegion to: " + str + ", indoor=" + z4 + ", setNow=" + z10);
        assertValidNativeCamera("Native instance pointer is null when calling setRegion()");
        nativeSetRegion(this.mNativeInstance, str, z4, z10);
    }

    @Deprecated
    public synchronized void subscribeStream(ThermalImageStreamListener thermalImageStreamListener) {
        assertValidNativeCamera("Native instance pointer is null when calling subscribeStream()");
        if (!isConnected()) {
            ThermalLog.w(TAG, "subscribeStream, Camera is not connected");
            return;
        }
        this.mStreamListeners.addListener(thermalImageStreamListener);
        if (!isGrabbing()) {
            this.mThermalImageStreamAddr = nativeStartStream(this.mNativeInstance, this.mStreamListeners);
        }
    }

    public void toggleLamp(boolean z4) {
        assertValidNativeCamera("Native instance pointer is null when calling toggleLamp()");
        nativeToggleLamp(this.mNativeInstance, z4);
    }

    @Deprecated
    public synchronized void unsubscribeAllStreams() {
        assertValidNativeCamera("Native instance pointer is null when calling unsubscribeAllStreams()");
        this.mStreamListeners.clear();
        nativeStopStream(this.mNativeInstance, this.mThermalImageStreamAddr);
        this.mThermalImageStreamAddr = 0L;
    }

    @Deprecated
    public synchronized void unsubscribeStream(ThermalImageStreamListener thermalImageStreamListener) {
        assertValidNativeCamera("Native instance pointer is null when calling unsubscribeStream()");
        if (!isConnected()) {
            ThermalLog.w(TAG, "unsubscribeStream, Camera is not connected");
            return;
        }
        this.mStreamListeners.removeListener(thermalImageStreamListener);
        if (this.mStreamListeners.isEmpty()) {
            unsubscribeAllStreams();
        }
    }

    @Deprecated
    public void withImage(ThermalImageStreamListener thermalImageStreamListener, com.flir.thermalsdk.utils.Consumer<ThermalImage> consumer) {
        withImage(consumer);
    }

    public void glOnSurfaceChanged(int i, int i3, float f10) {
        long j10 = this.glFrameRendererAddr;
        if (j10 == 0) {
            ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glOnSurfaceChanged()");
        } else {
            glOnSurfaceChangedNative(j10, i, i3, f10);
        }
    }

    public void glSetViewport(int i, int i3, int i6, int i10, float f10) {
        long j10 = this.glFrameRendererAddr;
        if (j10 == 0) {
            ThermalLog.e(TAG, "Native GLFrameRenderer instance pointer is null when calling glSetViewport()");
        } else {
            glSetViewportNative(j10, i, i3, i6, i10, f10);
        }
    }

    @Deprecated
    public synchronized void withImage(com.flir.thermalsdk.utils.Consumer<ThermalImage> consumer) {
        assertValidNativeStream("Native instance pointer is null when calling withImage()");
        nativeWithImage(this.mThermalImageStreamAddr, consumer);
    }

    public synchronized AuthenticationResponse authenticate(InetAddress inetAddress, String str, long j10) {
        throwIfNull(inetAddress, "Not allowed to call Camera#authenticate(..) with null ipaddress");
        throwIfNull(inetAddress.getHostAddress(), "Not allowed to call Camera#authenticate(..) with null InetAddress.getHostAddress()");
        return authenticate(nativeIpAddressToIdentity(inetAddress.getHostAddress()), str, j10);
    }

    public synchronized void connect(Identity identity, ConnectionStatusListener connectionStatusListener, ConnectParameters connectParameters) {
        throwIfNull(identity, "Not allowed to call Camera#connect(..) with null Identity");
        throwIfNull(connectionStatusListener, "Not allowed to call Camera#connect(..) with null listener");
        assertValidNativeCamera("Native instance pointer is null when calling connect()");
        nativeConnect(this.mNativeInstance, this.mInternalAuthResponse.authenticationResponseId, identity, Locale.getDefault().getCountry(), connectionStatusListener, connectParameters);
    }
}
