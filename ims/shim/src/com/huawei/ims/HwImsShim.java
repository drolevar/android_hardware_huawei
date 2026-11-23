/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.ims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HwBinder;
import android.os.HwParcel;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallForwardInfo;
import android.util.Log;
import com.android.ims.HwImsManager;
import com.android.internal.telephony.BaseCommands;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.IHwGsmCdmaPhoneEx;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneInternalInterface;
import com.android.internal.telephony.dataconnection.TransportManager;
import com.android.internal.telephony.imsphone.IHwImsPhoneEx;
import com.android.internal.telephony.imsphone.ImsPhone;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class HwImsShim {
    interface ImsSwitchSender {
        boolean send(String slotService, int state);
    }

    private static final String TAG = "HwImsShim";
    private static final String HW_RADIO_V2_0 = "vendor.huawei.hardware.radio@2.0::IRadio";
    private static final int TRANSACTION_SET_IMS_SWITCH = 4;
    private static final AtomicInteger SERIAL_GEN = new AtomicInteger(1000);
    private static final Object[] SLOT_LOCKS = {new Object(), new Object()};
    private static final int[] DESIRED_IMS_SWITCH = {-1, -1};
    private static final boolean[] SWITCH_WORKER_ACTIVE = {false, false};
    static volatile ImsSwitchSender sSwitchSender = HwImsShim::sendSetImsSwitchOnce;
    static volatile long sInitialRetryDelayMs = 500L;

    private static final Map<ImsCallForwardInfo, int[]> CF_TIMES = new WeakHashMap<>();

    private static final IHwImsPhoneEx DEFAULT_IMS_PHONE_EX =
            new IHwImsPhoneEx() {
                private volatile boolean mBusy;

                @Override
                public Message popUtMessage(int id) {
                    return null;
                }

                @Override
                public void setIsBusy(boolean busy) {
                    mBusy = busy;
                }
            };

    private HwImsShim() {}

    public static void onServiceCreated(Context context) {
        if (!SystemProperties.getBoolean("ro.config.hw_volte_on", true)) {
            return;
        }
        for (int slotId = 0; slotId <= 1; slotId++) {
            if (HwImsManager.isEnhanced4gLteModeSettingEnabledByUser(context, slotId)) {
                enableModemImsSwitch(slotId);
            } else {
                disableModemImsSwitch(slotId);
            }
        }
    }

    public static void enableModemImsSwitch(final int slotId) {
        setModemImsSwitchAsync(slotId, 1);
    }

    public static void disableModemImsSwitch(final int slotId) {
        setModemImsSwitchAsync(slotId, 0);
    }

    static void resetSwitchStateForTest() {
        for (int slotId = 0; slotId <= 1; slotId++) {
            synchronized (SLOT_LOCKS[slotId]) {
                DESIRED_IMS_SWITCH[slotId] = -1;
                SWITCH_WORKER_ACTIVE[slotId] = false;
            }
        }
    }

    static boolean isSwitchWorkerActiveForTest(int slotId) {
        synchronized (SLOT_LOCKS[slotId]) {
            return SWITCH_WORKER_ACTIVE[slotId];
        }
    }

    private static void setModemImsSwitchAsync(final int slotId, final int state) {
        if (slotId < 0 || slotId > 1) {
            return;
        }
        synchronized (SLOT_LOCKS[slotId]) {
            DESIRED_IMS_SWITCH[slotId] = state;
            if (SWITCH_WORKER_ACTIVE[slotId]) {
                return;
            }
            SWITCH_WORKER_ACTIVE[slotId] = true;
        }
        final String serviceName = (slotId == 0) ? "slot1" : "slot2";
        Thread worker =
                new Thread(
                        () -> {
                            boolean ownsActive = true;
                            try {
                                int lastSent = -1;
                                while (true) {
                                    int target;
                                    synchronized (SLOT_LOCKS[slotId]) {
                                        target = DESIRED_IMS_SWITCH[slotId];
                                        if (target < 0 || target == lastSent) {
                                            SWITCH_WORKER_ACTIVE[slotId] = false;
                                            ownsActive = false;
                                            return;
                                        }
                                    }
                                    boolean sent = false;
                                    long delayMs = sInitialRetryDelayMs;
                                    for (int attempt = 1; attempt <= 10; attempt++) {
                                        synchronized (SLOT_LOCKS[slotId]) {
                                            target = DESIRED_IMS_SWITCH[slotId];
                                        }
                                        if (sSwitchSender.send(serviceName, target)) {
                                            lastSent = target;
                                            sent = true;
                                            break;
                                        }
                                        try {
                                            Thread.sleep(delayMs);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            synchronized (SLOT_LOCKS[slotId]) {
                                                SWITCH_WORKER_ACTIVE[slotId] = false;
                                                ownsActive = false;
                                            }
                                            return;
                                        }
                                        delayMs = Math.min(delayMs * 2, 4000L);
                                    }
                                    if (!sent) {
                                        Log.w(
                                                TAG,
                                                "Timed out sending setImsSwitch("
                                                        + target
                                                        + ") on "
                                                        + serviceName);
                                        synchronized (SLOT_LOCKS[slotId]) {
                                            SWITCH_WORKER_ACTIVE[slotId] = false;
                                            ownsActive = false;
                                        }
                                        return;
                                    }
                                }
                            } finally {
                                if (ownsActive) {
                                    synchronized (SLOT_LOCKS[slotId]) {
                                        SWITCH_WORKER_ACTIVE[slotId] = false;
                                    }
                                }
                            }
                        },
                        "HwImsSwitch-" + serviceName);
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean sendSetImsSwitchOnce(String slotService, int state) {
        HwParcel req = new HwParcel();
        HwParcel reply = new HwParcel();
        try {
            IHwBinder binder = HwBinder.getService(HW_RADIO_V2_0, slotService, true);
            if (binder == null) {
                return false;
            }
            int serial = SERIAL_GEN.incrementAndGet();
            req.writeInterfaceToken(HW_RADIO_V2_0);
            req.writeInt32(serial);
            req.writeInt32(state);
            binder.transact(TRANSACTION_SET_IMS_SWITCH, req, reply, IBinder.FLAG_ONEWAY);
            req.releaseTemporaryStorage();
            Log.i(TAG, "Sent setImsSwitch(" + state + ") to " + slotService + " serial=" + serial);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setImsSwitch(" + state + ") on " + slotService + " retryable error: " + t);
            return false;
        } finally {
            try {
                reply.release();
            } catch (Throwable ignored) {
            }
        }
    }

    public static IHwGsmCdmaPhoneEx getHwGsmCdmaPhoneEx(final Phone phone) {
        return new IHwGsmCdmaPhoneEx() {
            @Override
            public void setCallForwardingOption(
                    int commandInterfaceCFAction,
                    int commandInterfaceCFReason,
                    String dialingNumber,
                    int serviceClass,
                    int timerSeconds,
                    Message onComplete) {
                if (phone != null) {
                    phone.setCallForwardingOption(
                            commandInterfaceCFAction,
                            commandInterfaceCFReason,
                            dialingNumber,
                            serviceClass,
                            timerSeconds,
                            onComplete);
                } else if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, null, new RuntimeException("Phone is null"));
                    onComplete.sendToTarget();
                }
            }
        };
    }

    public static IHwImsPhoneEx getHwImsPhoneEx(Phone phone) {
        return DEFAULT_IMS_PHONE_EX;
    }

    private static int[] getOrCreateCfTimes(ImsCallForwardInfo info) {
        synchronized (CF_TIMES) {
            int[] arr = CF_TIMES.get(info);
            if (arr == null) {
                arr = new int[] {-1, -1, -1, -1};
                CF_TIMES.put(info, arr);
            }
            return arr;
        }
    }

    public static void setStartHour(ImsCallForwardInfo info, int val) {
        if (info != null) getOrCreateCfTimes(info)[0] = val;
    }

    public static void setStartMinute(ImsCallForwardInfo info, int val) {
        if (info != null) getOrCreateCfTimes(info)[1] = val;
    }

    public static void setEndHour(ImsCallForwardInfo info, int val) {
        if (info != null) getOrCreateCfTimes(info)[2] = val;
    }

    public static void setEndMinute(ImsCallForwardInfo info, int val) {
        if (info != null) getOrCreateCfTimes(info)[3] = val;
    }

    public static int getStartHour(ImsCallForwardInfo info) {
        if (info == null) return -1;
        synchronized (CF_TIMES) {
            int[] arr = CF_TIMES.get(info);
            return arr != null ? arr[0] : -1;
        }
    }

    public static int getStartMinute(ImsCallForwardInfo info) {
        if (info == null) return -1;
        synchronized (CF_TIMES) {
            int[] arr = CF_TIMES.get(info);
            return arr != null ? arr[1] : -1;
        }
    }

    public static int getEndHour(ImsCallForwardInfo info) {
        if (info == null) return -1;
        synchronized (CF_TIMES) {
            int[] arr = CF_TIMES.get(info);
            return arr != null ? arr[2] : -1;
        }
    }

    public static int getEndMinute(ImsCallForwardInfo info) {
        if (info == null) return -1;
        synchronized (CF_TIMES) {
            int[] arr = CF_TIMES.get(info);
            return arr != null ? arr[3] : -1;
        }
    }

    public static void recordVoiceBandInfo(Object baseCommands, int[] bands) {}

    public static boolean getImsSwitch(Phone phone) {
        if (!SystemProperties.getBoolean("ro.config.hw_volte_on", true)) {
            return false;
        }
        int slotId = 0;
        Context ctx = null;
        if (phone != null) {
            try {
                slotId = phone.getPhoneId();
            } catch (Throwable ignored) {
            }
            try {
                ctx = phone.getContext();
            } catch (Throwable ignored) {
            }
        }
        if (slotId >= 0 && slotId <= 1) {
            synchronized (SLOT_LOCKS[slotId]) {
                if (DESIRED_IMS_SWITCH[slotId] >= 0) {
                    return DESIRED_IMS_SWITCH[slotId] == 1;
                }
            }
        } else {
            slotId = 0;
        }
        return HwImsManager.isEnhanced4gLteModeSettingEnabledByUser(ctx, slotId);
    }

    public static void setSpeechInfoCodec(Phone phone, int codec) {}

    public static String getSpeechInfoCodec(Phone phone) {
        return "";
    }

    public static void notifySuppServiceSuccessed(
            Phone phone, PhoneInternalInterface.SuppService suppService) {}

    public static void getCallForwardingOption(
            Phone phone, int reason, int serviceClass, Message onComplete) {
        if (phone == null) {
            if (onComplete != null) {
                AsyncResult.forMessage(onComplete, null, new RuntimeException("Phone is null"));
                onComplete.sendToTarget();
            }
            return;
        }
        try {
            phone.getCallForwardingOption(reason, serviceClass, onComplete);
        } catch (Throwable t) {
            phone.getCallForwardingOption(reason, onComplete);
        }
    }

    public static void getCallbarringOption(
            Phone phone, String facility, int serviceClass, Message onComplete) {
        if (phone == null) {
            if (onComplete != null) {
                AsyncResult.forMessage(onComplete, null, new RuntimeException("Phone is null"));
                onComplete.sendToTarget();
            }
            return;
        }
        phone.getCallBarring(facility, "", onComplete, serviceClass);
    }

    public static void setCallbarringOption(
            Phone phone,
            String facility,
            String password,
            boolean isActivate,
            int serviceClass,
            Message onComplete) {
        if (phone == null) {
            if (onComplete != null) {
                AsyncResult.forMessage(onComplete, null, new RuntimeException("Phone is null"));
                onComplete.sendToTarget();
            }
            return;
        }
        phone.setCallBarring(facility, isActivate, password != null ? password : "", onComplete, serviceClass);
    }

    public static void notifyECTFailed(
            ImsPhone phone, PhoneInternalInterface.SuppService suppService) {
        if (phone != null) {
            try {
                phone.notifySuppServiceFailed(suppService);
            } catch (Throwable ignored) {
            }
        }
    }

    public static TransportManager getTransportManager(Phone phone) {
        return TransportManager.INSTANCE;
    }

    public static CallManager getCallManager() {
        try {
            Phone defPhone = PhoneFactory.getDefaultPhone();
            Context ctx = defPhone != null ? defPhone.getContext() : null;
            return CallManager.getInstance(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "getCallManager fallback returned null", t);
            return null;
        }
    }

    public static void registerForNewRingingConnection(
            CallManager cm, Handler h, int what, Object obj) {
        if (h == null) {
            return;
        }
        try {
            Phone[] phones = PhoneFactory.getPhones();
            if (phones != null) {
                for (Phone p : phones) {
                    if (p != null) {
                        p.registerForNewRingingConnection(h, what, obj);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerForNewRingingConnection ignored", t);
        }
    }

    public static void registerForUnsolNvCfgFinished(
            CommandsInterface ci, Handler h, int what, Object obj) {}

    public static void setVoNrSwitch(CommandsInterface ci, int status, Message msg) {
        if (msg != null) {
            AsyncResult.forMessage(msg, null, null);
            msg.sendToTarget();
        }
    }

    public static void unregisterForSrvccStateChanged(CommandsInterface ci, Handler h) {
        if (ci == null || h == null) {
            return;
        }
        try {
            Method m = ci.getClass().getMethod("unregisterForSrvccStateChanged", Handler.class);
            m.invoke(ci, h);
        } catch (Throwable ignored) {
        }
    }

    public static void notifyExitEmergencyCallbackModeRegistrants(BaseCommands bc) {
        if (bc == null) {
            return;
        }
        try {
            Field f = BaseCommands.class.getDeclaredField("mExitEmergencyCallbackModeRegistrants");
            f.setAccessible(true);
            Object rl = f.get(bc);
            if (rl != null) {
                Method nm = rl.getClass().getMethod("notifyRegistrants");
                nm.invoke(rl);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void unregisterForExitEmergencyCallbackMode(BaseCommands bc, Handler h) {
        if (bc == null || h == null) {
            return;
        }
        try {
            Field f = BaseCommands.class.getDeclaredField("mExitEmergencyCallbackModeRegistrants");
            f.setAccessible(true);
            Object rl = f.get(bc);
            if (rl != null) {
                Method rm = rl.getClass().getMethod("remove", Handler.class);
                rm.invoke(rl, h);
            }
        } catch (Throwable ignored) {
        }
    }

    public static int getCurrentPhoneTypeForSlot(TelephonyManager tm, int slotId) {
        try {
            Phone phone = PhoneFactory.getPhone(slotId);
            if (phone != null) {
                return phone.getPhoneType();
            }
        } catch (Throwable ignored) {
        }
        return TelephonyManager.PHONE_TYPE_GSM;
    }

    public static void requestNetwork(
            ConnectivityManager cm,
            NetworkRequest req,
            ConnectivityManager.NetworkCallback cb,
            int timeoutMs,
            int legacyType,
            Handler h) {
        if (cm == null || req == null || cb == null) {
            return;
        }
        try {
            Method m =
                    ConnectivityManager.class.getMethod(
                            "requestNetwork",
                            NetworkRequest.class,
                            int.class,
                            int.class,
                            Handler.class,
                            ConnectivityManager.NetworkCallback.class);
            m.invoke(cm, req, timeoutMs, legacyType, h, cb);
            return;
        } catch (Throwable ignored) {
        }
        try {
            if (h != null && timeoutMs > 0) {
                cm.requestNetwork(req, cb, h, timeoutMs);
            } else if (h != null) {
                cm.requestNetwork(req, cb, h);
            } else {
                cm.requestNetwork(req, cb);
            }
        } catch (Throwable t) {
            Log.w(TAG, "requestNetwork failed", t);
        }
    }

    public static boolean satisfiedBy(NetworkSpecifier self, NetworkSpecifier other) {
        if (self == null) {
            return false;
        }
        if (Objects.equals(self, other)) {
            return true;
        }
        try {
            if (self.canBeSatisfiedBy(other)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        if (self instanceof TelephonyNetworkSpecifier
                && other != null
                && "android.net.StringNetworkSpecifier".equals(other.getClass().getName())) {
            try {
                int subId = ((TelephonyNetworkSpecifier) self).getSubscriptionId();
                Object spec = other.getClass().getField("specifier").get(other);
                return Objects.equals(Integer.toString(subId), spec);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static ArrayList<Byte> primitiveArrayToArrayList(byte[] arr) {
        ArrayList<Byte> list = new ArrayList<>(arr != null ? arr.length : 0);
        if (arr != null) {
            for (byte b : arr) {
                list.add(b);
            }
        }
        return list;
    }

    public static byte[] arrayListToPrimitiveArray(ArrayList<Byte> list) {
        if (list == null) {
            return new byte[0];
        }
        byte[] out = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Byte b = list.get(i);
            out[i] = b != null ? b : 0;
        }
        return out;
    }
}
