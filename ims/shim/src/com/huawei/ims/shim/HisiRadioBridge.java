/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.ims.shim;

import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.HwBinder;
import android.os.HwBlob;
import android.os.HwParcel;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.telephony.UiccAuthResponse;
import android.util.Log;
import com.android.internal.telephony.IPhoneCallback;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * HwBinder client/callback bridge for {@code vendor.huawei.hardware.hisiradio@1.0::IHisiRadio}
 * ({@code slot1} / {@code slot2}), forwarding VoWiFi <-> IMSA tunnel messages, IMS domain config,
 * and USIM EAP-AKA authentication for Huawei {@code Mapcon}.
 */
public final class HisiRadioBridge {
    private static final String TAG = "HisiRadioBridge";

    public static final int PHONE_EVENT_RADIO_AVAILABLE = 1;
    public static final int PHONE_EVENT_RADIO_UNAVAILABLE = 2;
    public static final int PHONE_EVENT_IMSA_TO_MAPCON = 4;

    private static final String IFACE_HISI_RADIO_1_0 =
            "vendor.huawei.hardware.hisiradio@1.0::IHisiRadio";
    private static final String IFACE_HISI_RADIO_RSP_1_0 =
            "vendor.huawei.hardware.hisiradio@1.0::IHisiRadioResponse";
    private static final String IFACE_HISI_RADIO_RSP_1_1 =
            "vendor.huawei.hardware.hisiradio@1.1::IHisiRadioResponse";
    private static final String IFACE_HISI_RADIO_RSP_1_2 =
            "vendor.huawei.hardware.hisiradio@1.2::IHisiRadioResponse";
    private static final String IFACE_HISI_RADIO_RSP_1_3 =
            "vendor.huawei.hardware.hisiradio@1.3::IHisiRadioResponse";
    private static final String IFACE_HISI_RADIO_IND_1_0 =
            "vendor.huawei.hardware.hisiradio@1.0::IHisiRadioIndication";
    private static final String IFACE_HISI_RADIO_IND_1_1 =
            "vendor.huawei.hardware.hisiradio@1.1::IHisiRadioIndication";
    private static final String IFACE_HISI_RADIO_IND_1_2 =
            "vendor.huawei.hardware.hisiradio@1.2::IHisiRadioIndication";
    private static final String IFACE_HISI_RADIO_IND_1_3 =
            "vendor.huawei.hardware.hisiradio@1.3::IHisiRadioIndication";
    private static final String IFACE_SERVICE_MANAGER_1_0 =
            "android.hidl.manager@1.0::IServiceManager";
    private static final String IFACE_SERVICE_NOTIFICATION_1_0 =
            "android.hidl.manager@1.0::IServiceNotification";
    private static final String IFACE_IBASE = "android.hidl.base@1.0::IBase";

    private static final int TX_SM_REGISTER_FOR_NOTIFICATIONS = 6;
    private static final int TX_SN_ON_REGISTRATION = 1;

    // IHisiRadio@1.0 request transaction codes (verified via REA/IDA on hisiradio@1.0.so)
    private static final int TX_SET_RESPONSE_FUNCTIONS = 1;
    private static final int TX_SET_IMS_DOMAIN = 16;
    private static final int TX_VOWIFI_TO_IMSA_MSG = 17;
    private static final int TX_UICC_AUTH = 18;
    private static final int TX_GET_IMS_DOMAIN = 64;

    // IHisiRadioResponse@1.0 callback transaction codes
    private static final int RSP_COMMON_MSG = 1;
    private static final int RSP_UICC_AUTH = 12;
    private static final int MSG_ID_GET_IMS_DOMAIN = 662;

    // IHisiRadioIndication@1.0 callback transaction codes
    private static final int IND_IMSA_TO_VOWIFI_MSG = 5;

    // HIDL meta-transactions on android.hidl.base@1.0::IBase
    private static final int HIDL_INTERFACE_CHAIN = 256067662;
    private static final int HIDL_DEBUG = 256131655;
    private static final int HIDL_INTERFACE_DESCRIPTOR = 256136003;
    private static final int HIDL_GET_HASH_CHAIN = 256398152;
    private static final int HIDL_SET_HAL_INSTRUMENTATION = 256462420;
    private static final int HIDL_PING = 256921159;
    private static final int HIDL_NOTIFY_SYSPROPS_CHANGED = 257120595;

    private static final long UICC_AUTH_TIMEOUT_MS = 8000L;
    private static final long GET_IMS_DOMAIN_TIMEOUT_MS = 2000L;

    interface HwServiceGetter {
        IHwBinder getService(String iface, String name, boolean retry) throws RemoteException;
    }

    static volatile HwServiceGetter sServiceGetter = HwBinder::getService;
    static volatile long sReconnectInitialDelayMs = 250L;

    private static final class Holder {
        static final HisiRadioBridge INSTANCE = new HisiRadioBridge();
    }

    private static final class ListenerEntry {
        final int phoneId;
        final IPhoneCallback callback;
        final int events;
        final AtomicBoolean notifiedAvailable = new AtomicBoolean(false);

        ListenerEntry(int phoneId, IPhoneCallback callback, int events) {
            this.phoneId = phoneId;
            this.callback = callback;
            this.events = events;
        }
    }

    private static final class PendingAuth {
        final ConditionVariable done = new ConditionVariable(false);
        volatile UiccAuthResponse response;
    }

    private static final class PendingDomain {
        final ConditionVariable done = new ConditionVariable(false);
        volatile int domain = -1;
    }

    private final AtomicInteger mSerialGen = new AtomicInteger(20000);
    private final Object[] mSlotLocks = {new Object(), new Object()};
    private final Object[] mReconnectLocks = {new Object(), new Object()};
    private final boolean[] mReconnectWorkerActive = {false, false};
    private final boolean[] mReconnectRequested = {false, false};
    private final boolean[] mNotifRegistered = {false, false};
    private final IHwBinder[] mRadioBinders = new IHwBinder[2];
    private final ResponseStub[] mResponseStubs = new ResponseStub[2];
    private final IndicationStub[] mIndicationStubs = new IndicationStub[2];
    private final ServiceNotificationStub[] mNotificationStubs = new ServiceNotificationStub[2];
    private final AtomicIntegerArray mCachedImsDomain = new AtomicIntegerArray(new int[] {0, 0});
    private final List<ListenerEntry> mListeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, PendingAuth> mPendingAuths = new ConcurrentHashMap<>();
    private final Map<Integer, PendingDomain> mPendingDomains = new ConcurrentHashMap<>();

    private HisiRadioBridge() {}

    public static HisiRadioBridge getInstance() {
        return Holder.INSTANCE;
    }

    private static boolean isValidSlot(int phoneId) {
        return phoneId == 0 || phoneId == 1;
    }

    private boolean isSlotConnected(int phoneId) {
        synchronized (mSlotLocks[phoneId]) {
            return mRadioBinders[phoneId] != null;
        }
    }

    private boolean hasListenersForSlot(int phoneId) {
        for (ListenerEntry entry : mListeners) {
            if (entry.phoneId == phoneId) {
                return true;
            }
        }
        return false;
    }

    void resetForTest() {
        mListeners.clear();
        for (int i = 0; i <= 1; i++) {
            synchronized (mSlotLocks[i]) {
                mRadioBinders[i] = null;
            }
            synchronized (mReconnectLocks[i]) {
                mNotifRegistered[i] = false;
                mReconnectRequested[i] = false;
            }
        }
    }

    private IHwBinder ensureSlotConnected(final int phoneId) {
        if (!isValidSlot(phoneId)) {
            return null;
        }
        IHwBinder connectedBinder = null;
        boolean newlyConnected = false;
        synchronized (mSlotLocks[phoneId]) {
            if (mRadioBinders[phoneId] != null) {
                return mRadioBinders[phoneId];
            }
            if (mResponseStubs[phoneId] == null) {
                mResponseStubs[phoneId] = new ResponseStub(phoneId);
            }
            if (mIndicationStubs[phoneId] == null) {
                mIndicationStubs[phoneId] = new IndicationStub(phoneId);
            }
            final String svcName = (phoneId == 0) ? "slot1" : "slot2";
            HwParcel req = new HwParcel();
            HwParcel reply = new HwParcel();
            try {
                // Non-blocking lookup (retry=false) so com.android.phone main thread never stalls.
                IHwBinder binder = sServiceGetter.getService(IFACE_HISI_RADIO_1_0, svcName, false);
                if (binder == null) {
                    return null;
                }
                req.writeInterfaceToken(IFACE_HISI_RADIO_1_0);
                req.writeStrongBinder(mResponseStubs[phoneId]);
                req.writeStrongBinder(mIndicationStubs[phoneId]);
                binder.transact(TX_SET_RESPONSE_FUNCTIONS, req, reply, 0);
                reply.verifySuccess();
                req.releaseTemporaryStorage();

                final IHwBinder boundBinder = binder;
                final long cookie = phoneId + 1L;
                binder.linkToDeath(
                        serviceCookie -> {
                            boolean cleared = false;
                            synchronized (mSlotLocks[phoneId]) {
                                if (mRadioBinders[phoneId] == boundBinder) {
                                    mRadioBinders[phoneId] = null;
                                    cleared = true;
                                }
                            }
                            if (cleared) {
                                notifyRadioEvent(phoneId, PHONE_EVENT_RADIO_UNAVAILABLE);
                                if (hasListenersForSlot(phoneId)) {
                                    scheduleReconnect(phoneId);
                                }
                            }
                        },
                        cookie);
                mRadioBinders[phoneId] = binder;
                connectedBinder = binder;
                newlyConnected = true;
                Log.i(TAG, "Bound IHisiRadio " + svcName + " callbacks");
            } catch (Throwable t) {
                Log.w(TAG, "Unable to bind IHisiRadio " + svcName + ": " + t);
                return null;
            } finally {
                try {
                    reply.release();
                } catch (Throwable ignored) {
                }
            }
        }
        if (newlyConnected) {
            notifyRadioEvent(phoneId, PHONE_EVENT_RADIO_AVAILABLE);
        }
        return connectedBinder;
    }

    private void ensureServiceNotificationRegistered(final int phoneId) {
        synchronized (mReconnectLocks[phoneId]) {
            if (mNotifRegistered[phoneId]) {
                return;
            }
            if (mNotificationStubs[phoneId] == null) {
                mNotificationStubs[phoneId] = new ServiceNotificationStub(phoneId);
            }
        }
        final String svcName = (phoneId == 0) ? "slot1" : "slot2";
        HwParcel req = new HwParcel();
        HwParcel reply = new HwParcel();
        try {
            IHwBinder smBinder =
                    sServiceGetter.getService(IFACE_SERVICE_MANAGER_1_0, "default", false);
            if (smBinder == null) {
                return;
            }
            req.writeInterfaceToken(IFACE_SERVICE_MANAGER_1_0);
            req.writeString(IFACE_HISI_RADIO_1_0);
            req.writeString(svcName);
            req.writeStrongBinder(mNotificationStubs[phoneId]);
            smBinder.transact(TX_SM_REGISTER_FOR_NOTIFICATIONS, req, reply, 0);
            reply.verifySuccess();
            req.releaseTemporaryStorage();
            if (reply.readBool()) {
                synchronized (mReconnectLocks[phoneId]) {
                    mNotifRegistered[phoneId] = true;
                }
                smBinder.linkToDeath(
                        cookie -> {
                            synchronized (mReconnectLocks[phoneId]) {
                                mNotifRegistered[phoneId] = false;
                            }
                            if (hasListenersForSlot(phoneId)) {
                                scheduleReconnect(phoneId);
                            }
                        },
                        100L + phoneId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to register IHisiRadio service notification for " + svcName + ": " + t);
        } finally {
            try {
                reply.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void scheduleReconnect(final int phoneId) {
        if (!isValidSlot(phoneId)) {
            return;
        }
        synchronized (mReconnectLocks[phoneId]) {
            mReconnectRequested[phoneId] = true;
            if (mReconnectWorkerActive[phoneId]) {
                return;
            }
            mReconnectWorkerActive[phoneId] = true;
        }
        final String svcName = (phoneId == 0) ? "slot1" : "slot2";
        Thread worker =
                new Thread(
                        () -> {
                            boolean ownsActive = true;
                            try {
                                while (true) {
                                    synchronized (mReconnectLocks[phoneId]) {
                                        mReconnectRequested[phoneId] = false;
                                    }
                                    ensureServiceNotificationRegistered(phoneId);
                                    if (hasListenersForSlot(phoneId) && !isSlotConnected(phoneId)) {
                                        long delayMs = sReconnectInitialDelayMs;
                                        for (int attempt = 1; attempt <= 12; attempt++) {
                                            if (!hasListenersForSlot(phoneId)
                                                    || ensureSlotConnected(phoneId) != null) {
                                                break;
                                            }
                                            ensureServiceNotificationRegistered(phoneId);
                                            try {
                                                Thread.sleep(delayMs);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                return;
                                            }
                                            delayMs = Math.min(delayMs * 2L, 4000L);
                                        }
                                    }
                                    synchronized (mReconnectLocks[phoneId]) {
                                        if (!mReconnectRequested[phoneId]) {
                                            mReconnectWorkerActive[phoneId] = false;
                                            ownsActive = false;
                                            return;
                                        }
                                    }
                                }
                            } finally {
                                if (ownsActive) {
                                    synchronized (mReconnectLocks[phoneId]) {
                                        mReconnectWorkerActive[phoneId] = false;
                                    }
                                }
                            }
                        },
                        "HisiRadioReconnect-" + svcName);
        worker.setDaemon(true);
        worker.start();
    }

    public boolean registerForPhoneEvent(int phoneId, IPhoneCallback callback, int events) {
        if (!isValidSlot(phoneId) || callback == null || events == 0) {
            return false;
        }
        ListenerEntry entry = new ListenerEntry(phoneId, callback, events);
        mListeners.add(entry);
        IHwBinder binder = ensureSlotConnected(phoneId);
        if (binder != null && (events & PHONE_EVENT_RADIO_AVAILABLE) != 0) {
            if (entry.notifiedAvailable.compareAndSet(false, true)) {
                try {
                    callback.onCallback1(phoneId);
                } catch (RemoteException ignored) {
                }
            }
        }
        scheduleReconnect(phoneId);
        return true;
    }

    public void unregisterForPhoneEvent(IPhoneCallback callback) {
        if (callback == null) {
            return;
        }
        IBinder target = callback.asBinder();
        mListeners.removeIf(
                entry ->
                        entry.callback == callback
                                || (target != null && target.equals(entry.callback.asBinder())));
    }

    private void notifyRadioEvent(int phoneId, int eventBit) {
        for (ListenerEntry entry : mListeners) {
            if (entry.phoneId != phoneId) {
                continue;
            }
            if (eventBit == PHONE_EVENT_RADIO_UNAVAILABLE) {
                entry.notifiedAvailable.set(false);
                if ((entry.events & PHONE_EVENT_RADIO_UNAVAILABLE) != 0) {
                    try {
                        entry.callback.onCallback1(phoneId);
                    } catch (RemoteException ignored) {
                    }
                }
            } else if (eventBit == PHONE_EVENT_RADIO_AVAILABLE) {
                if ((entry.events & PHONE_EVENT_RADIO_AVAILABLE) != 0
                        && entry.notifiedAvailable.compareAndSet(false, true)) {
                    try {
                        entry.callback.onCallback1(phoneId);
                    } catch (RemoteException ignored) {
                    }
                }
            }
        }
    }

    private void notifyImsaToMapcon(int phoneId, byte[] payload) {
        if (payload == null) {
            return;
        }
        for (ListenerEntry entry : mListeners) {
            if (entry.phoneId == phoneId && (entry.events & PHONE_EVENT_IMSA_TO_MAPCON) != 0) {
                Bundle bundle = new Bundle();
                bundle.putByteArray("imsa2mapcon_msg", payload.clone());
                try {
                    entry.callback.onCallback3(phoneId, 0, bundle);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    public boolean vowifiToImsaMsg(int phoneId, byte[] msg) {
        if (!isValidSlot(phoneId) || msg == null) {
            return false;
        }
        IHwBinder binder = ensureSlotConnected(phoneId);
        if (binder == null) {
            return false;
        }
        HwParcel req = new HwParcel();
        HwParcel reply = new HwParcel();
        try {
            int serial = mSerialGen.incrementAndGet();
            req.writeInterfaceToken(IFACE_HISI_RADIO_1_0);
            req.writeInt32(serial);
            ArrayList<Byte> vec = new ArrayList<>(msg.length);
            for (byte b : msg) {
                vec.add(b);
            }
            req.writeInt8Vector(vec);
            binder.transact(TX_VOWIFI_TO_IMSA_MSG, req, reply, IBinder.FLAG_ONEWAY);
            req.releaseTemporaryStorage();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "vowifiToImsaMsg failed on phoneId=" + phoneId, t);
            return false;
        } finally {
            try {
                reply.release();
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean setImsDomain(int phoneId, int domainType) {
        if (!isValidSlot(phoneId)) {
            return false;
        }
        mCachedImsDomain.set(phoneId, domainType);
        IHwBinder binder = ensureSlotConnected(phoneId);
        if (binder == null) {
            return false;
        }
        HwParcel req = new HwParcel();
        HwParcel reply = new HwParcel();
        try {
            int serial = mSerialGen.incrementAndGet();
            req.writeInterfaceToken(IFACE_HISI_RADIO_1_0);
            req.writeInt32(serial);
            req.writeInt32(domainType);
            binder.transact(TX_SET_IMS_DOMAIN, req, reply, IBinder.FLAG_ONEWAY);
            req.releaseTemporaryStorage();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setImsDomain failed on phoneId=" + phoneId, t);
            return false;
        } finally {
            try {
                reply.release();
            } catch (Throwable ignored) {
            }
        }
    }

    public int getImsDomain(int phoneId) {
        if (!isValidSlot(phoneId)) {
            return 0;
        }
        IHwBinder binder = ensureSlotConnected(phoneId);
        if (binder == null) {
            return mCachedImsDomain.get(phoneId);
        }
        int serial = mSerialGen.incrementAndGet();
        PendingDomain pending = new PendingDomain();
        mPendingDomains.put(serial, pending);
        HwParcel req = new HwParcel();
        HwParcel reply = new HwParcel();
        try {
            req.writeInterfaceToken(IFACE_HISI_RADIO_1_0);
            req.writeInt32(serial);
            binder.transact(TX_GET_IMS_DOMAIN, req, reply, IBinder.FLAG_ONEWAY);
            req.releaseTemporaryStorage();
            if (pending.done.block(GET_IMS_DOMAIN_TIMEOUT_MS) && pending.domain >= 0) {
                mCachedImsDomain.set(phoneId, pending.domain);
                return pending.domain;
            }
        } catch (Throwable t) {
            Log.w(TAG, "getImsDomain fallback to cached domain on phoneId=" + phoneId, t);
        } finally {
            mPendingDomains.remove(serial);
            try {
                reply.release();
            } catch (Throwable ignored) {
            }
        }
        return mCachedImsDomain.get(phoneId);
    }

    public UiccAuthResponse uiccAuth(int phoneId, int authType, byte[] rand, byte[] auth) {
        if (!isValidSlot(phoneId) || rand == null || auth == null) {
            return null;
        }
        IHwBinder binder = ensureSlotConnected(phoneId);
        if (binder == null) {
            return null;
        }
        int serial = mSerialGen.incrementAndGet();
        PendingAuth pending = new PendingAuth();
        mPendingAuths.put(serial, pending);
        HwParcel req = new HwParcel();
        HwParcel reply = new HwParcel();
        try {
            String randHex = bytesToHex(rand);
            String authHex = bytesToHex(auth);
            req.writeInterfaceToken(IFACE_HISI_RADIO_1_0);
            req.writeInt32(serial);
            // RILUICCAUTH (0x38 = 56 bytes, verified via REA/IDA on hisiradio@1.0.so + libril.so):
            //   +0:  int32 authType
            //   +8:  int32 randLen (ASCII hex length passed to AT^UICCAUTH)
            //   +16: hidl_string randHex
            //   +32: int32 authLen (ASCII hex length passed to AT^UICCAUTH)
            //   +40: hidl_string authHex
            HwBlob blob = new HwBlob(56);
            blob.putInt32(0, authType);
            blob.putInt32(8, randHex.length());
            blob.putString(16, randHex);
            blob.putInt32(32, authHex.length());
            blob.putString(40, authHex);
            req.writeBuffer(blob);
            binder.transact(TX_UICC_AUTH, req, reply, IBinder.FLAG_ONEWAY);
            req.releaseTemporaryStorage();
            if (pending.done.block(UICC_AUTH_TIMEOUT_MS)) {
                return pending.response;
            }
            Log.w(TAG, "Timed out waiting for uiccAuthResponse on phoneId=" + phoneId);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "uiccAuth failed on phoneId=" + phoneId, t);
            return null;
        } finally {
            mPendingAuths.remove(serial);
            try {
                reply.release();
            } catch (Throwable ignored) {
            }
        }
    }

    static UiccAuthResponse parseUiccAuthResponseBlob(HwParcel parcel, HwBlob blob) {
        UiccAuthResponse out = new UiccAuthResponse();
        int result = blob.getInt32(0);
        out.mResult = result;
        // RILUICCAUTHRESPONSE (104 bytes, verified via REA/IDA on hisiradio@1.0.so + libril.so):
        //   +8..+31:  RES  (int32 present @ +8,  hidl_string @ +16)
        //   +32..+55: IK   (int32 present @ +32, hidl_string @ +40)
        //   +56..+79: CK   (int32 present @ +56, hidl_string @ +64)
        //   +80..+103:AUTS (int32 present @ +80, hidl_string @ +88)
        // Read all 4 embedded hidl_strings in parcel order to match writeEmbeddedToParcel.
        UiccAuthResponse.UiccAuthResponseData res = readAuthData(parcel, blob, 8);
        UiccAuthResponse.UiccAuthResponseData ik = readAuthData(parcel, blob, 32);
        UiccAuthResponse.UiccAuthResponseData ck = readAuthData(parcel, blob, 56);
        UiccAuthResponse.UiccAuthResponseData auts = readAuthData(parcel, blob, 80);
        if (result == UiccAuthResponse.AUTH_RESP_SUCCESS) {
            out.mUiccAuthChallenge.mResData = res;
            out.mUiccAuthChallenge.mIkData = ik;
            out.mUiccAuthChallenge.mCkData = ck;
        } else if (result == UiccAuthResponse.AUTH_RESP_SYNC_FAIL) {
            out.mUiccAuthSyncFail = auts;
        }
        return out;
    }

    private static UiccAuthResponse.UiccAuthResponseData readAuthData(
            HwParcel parcel, HwBlob blob, long baseOffset) {
        int present = blob.getInt32(baseOffset);
        String hex = readEmbeddedStringCompat(parcel, blob, baseOffset + 8);
        byte[] decoded = hexToBytes(hex);
        return new UiccAuthResponse.UiccAuthResponseData(present, decoded);
    }

    private static String readEmbeddedStringCompat(
            HwParcel parcel, HwBlob blob, long fieldOffset) {
        String val = "";
        try {
            String raw = blob.getString(fieldOffset);
            if (raw != null) {
                val = raw;
            }
        } catch (Throwable ignored) {
        }
        if (parcel != null) {
            try {
                parcel.readEmbeddedBuffer(
                        val.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L,
                        blob.handle(),
                        fieldOffset,
                        false);
            } catch (Throwable ignored) {
            }
        }
        return val;
    }

    static String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(out);
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        String clean = hex.trim();
        int len = clean.length();
        if (len == 0 || (len & 1) != 0) {
            return new byte[0];
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(clean.charAt(i), 16);
            int lo = Character.digit(clean.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                return new byte[0];
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static boolean handleHidlBaseMeta(
            int code, HwParcel data, HwParcel reply, ArrayList<String> chain) {
        switch (code) {
            case HIDL_INTERFACE_CHAIN:
                data.enforceInterface(IFACE_IBASE);
                reply.writeStatus(HwParcel.STATUS_SUCCESS);
                reply.writeStringVector(chain);
                reply.send();
                return true;
            case HIDL_INTERFACE_DESCRIPTOR:
                data.enforceInterface(IFACE_IBASE);
                reply.writeStatus(HwParcel.STATUS_SUCCESS);
                reply.writeString(chain.get(0));
                reply.send();
                return true;
            case HIDL_GET_HASH_CHAIN:
                data.enforceInterface(IFACE_IBASE);
                reply.writeStatus(HwParcel.STATUS_SUCCESS);
                HwBlob hashVec = new HwBlob(16);
                int count = chain.size();
                hashVec.putInt32(8, count);
                hashVec.putBool(12, false);
                HwBlob hashes = new HwBlob(count * 32);
                hashVec.putBlob(0, hashes);
                reply.writeBuffer(hashVec);
                reply.send();
                return true;
            case HIDL_DEBUG:
            case HIDL_PING:
                data.enforceInterface(IFACE_IBASE);
                reply.writeStatus(HwParcel.STATUS_SUCCESS);
                reply.send();
                return true;
            case HIDL_SET_HAL_INSTRUMENTATION:
            case HIDL_NOTIFY_SYSPROPS_CHANGED:
                data.enforceInterface(IFACE_IBASE);
                return true;
            default:
                return false;
        }
    }

    private final class ResponseStub extends HwBinder {
        private final int mPhoneId;
        private final ArrayList<String> mChain =
                new ArrayList<>(
                        Arrays.asList(
                                IFACE_HISI_RADIO_RSP_1_3,
                                IFACE_HISI_RADIO_RSP_1_2,
                                IFACE_HISI_RADIO_RSP_1_1,
                                IFACE_HISI_RADIO_RSP_1_0,
                                IFACE_IBASE));

        ResponseStub(int phoneId) {
            mPhoneId = phoneId;
        }

        @Override
        public android.os.IHwInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public boolean linkToDeath(DeathRecipient recipient, long cookie) {
            return true;
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient) {
            return true;
        }

        @Override
        public void onTransact(int code, HwParcel data, HwParcel reply, int flags)
                throws RemoteException {
            if (handleHidlBaseMeta(code, data, reply, mChain)) {
                return;
            }
            if (code == RSP_UICC_AUTH) {
                data.enforceInterface(IFACE_HISI_RADIO_RSP_1_0);
                HwBlob infoBlob = data.readBuffer(12);
                int serial = infoBlob.getInt32(4);
                int error = infoBlob.getInt32(8);
                HwBlob authBlob = data.readBuffer(104);
                PendingAuth pending = mPendingAuths.get(serial);
                if (pending != null) {
                    if (error == 0) {
                        pending.response = parseUiccAuthResponseBlob(data, authBlob);
                    } else {
                        UiccAuthResponse fail = new UiccAuthResponse();
                        fail.mResult = UiccAuthResponse.AUTH_RESP_FAIL;
                        pending.response = fail;
                    }
                    pending.done.open();
                }
                return;
            }
            if (code == RSP_COMMON_MSG) {
                try {
                    data.enforceInterface(IFACE_HISI_RADIO_RSP_1_0);
                    HwBlob infoBlob = data.readBuffer(12);
                    int serial = infoBlob.getInt32(4);
                    int error = infoBlob.getInt32(8);
                    int msgId = data.readInt32();
                    if (msgId == MSG_ID_GET_IMS_DOMAIN) {
                        PendingDomain pending = mPendingDomains.get(serial);
                        if (pending != null) {
                            if (error == 0) {
                                HwBlob payload = data.readBuffer(56);
                                int vecLen = payload.getInt32(16);
                                if (vecLen > 0) {
                                    HwBlob ints =
                                            data.readEmbeddedBuffer(
                                                    vecLen * 4L, payload.handle(), 8, true);
                                    pending.domain = ints.getInt32(0);
                                }
                            }
                            pending.done.open();
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "commonRsp parse error on phoneId=" + mPhoneId + ": " + t);
                }
                return;
            }
            if ((flags & IBinder.FLAG_ONEWAY) == 0) {
                reply.writeStatus(HwParcel.STATUS_SUCCESS);
                reply.send();
            }
        }
    }

    private final class IndicationStub extends HwBinder {
        private final int mPhoneId;
        private final ArrayList<String> mChain =
                new ArrayList<>(
                        Arrays.asList(
                                IFACE_HISI_RADIO_IND_1_3,
                                IFACE_HISI_RADIO_IND_1_2,
                                IFACE_HISI_RADIO_IND_1_1,
                                IFACE_HISI_RADIO_IND_1_0,
                                IFACE_IBASE));

        IndicationStub(int phoneId) {
            mPhoneId = phoneId;
        }

        @Override
        public android.os.IHwInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public boolean linkToDeath(DeathRecipient recipient, long cookie) {
            return true;
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient) {
            return true;
        }

        @Override
        public void onTransact(int code, HwParcel data, HwParcel reply, int flags)
                throws RemoteException {
            if (handleHidlBaseMeta(code, data, reply, mChain)) {
                return;
            }
            if (code == IND_IMSA_TO_VOWIFI_MSG) {
                data.enforceInterface(IFACE_HISI_RADIO_IND_1_0);
                data.readInt32(); // RadioIndicationType
                ArrayList<Byte> vec = data.readInt8Vector();
                byte[] bytes = new byte[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    Byte b = vec.get(i);
                    bytes[i] = b != null ? b : 0;
                }
                notifyImsaToMapcon(mPhoneId, bytes);
                return;
            }
            if ((flags & IBinder.FLAG_ONEWAY) == 0) {
                reply.writeStatus(HwParcel.STATUS_SUCCESS);
                reply.send();
            }
        }
    }

    private final class ServiceNotificationStub extends HwBinder {
        private final int mPhoneId;
        private final ArrayList<String> mChain =
                new ArrayList<>(Arrays.asList(IFACE_SERVICE_NOTIFICATION_1_0, IFACE_IBASE));

        ServiceNotificationStub(int phoneId) {
            mPhoneId = phoneId;
        }

        @Override
        public android.os.IHwInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public boolean linkToDeath(DeathRecipient recipient, long cookie) {
            return true;
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient) {
            return true;
        }

        @Override
        public void onTransact(int code, HwParcel data, HwParcel reply, int flags)
                throws RemoteException {
            if (handleHidlBaseMeta(code, data, reply, mChain)) {
                return;
            }
            if (code == TX_SN_ON_REGISTRATION) {
                data.enforceInterface(IFACE_SERVICE_NOTIFICATION_1_0);
                data.readString(); // fqName
                data.readString(); // name
                data.readBool(); // preexisting
                if (hasListenersForSlot(mPhoneId)) {
                    scheduleReconnect(mPhoneId);
                }
                return;
            }
            if ((flags & IBinder.FLAG_ONEWAY) == 0) {
                reply.writeStatus(HwParcel.STATUS_SUCCESS);
                reply.send();
            }
        }
    }
}
