/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.ims;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneStateListener;
import android.telephony.RadioAccessFamily;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallForwardInfo;
import android.telephony.ims.stub.ImsRegistrationImplBase;
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
import com.android.internal.telephony.ProxyController;
import com.android.internal.telephony.dataconnection.TransportManager;
import com.android.internal.telephony.imsphone.IHwImsPhoneEx;
import com.android.internal.telephony.imsphone.ImsPhone;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

public final class HwImsShim {
    interface ImsSwitchSender {
        boolean send(String slotService, int state);
    }

    private static final String TAG = "HwImsShim";
    private static final String HW_RADIO_V2_0 = "vendor.huawei.hardware.radio@2.0::IRadio";
    private static final String DUAL_SIM_COORD_PROP =
            "com.huawei.ims.dualsim_coordinator_registered";
    private static final String DESIRED_IMS_SWITCH_PROP_PREFIX =
            "com.huawei.ims.desired_switch.slot";
    private static final int TRANSACTION_SET_IMS_SWITCH = 4;
    private static final long DDS_ASSIGN_DEBOUNCE_MS = 2_000L;
    private static final long RADIO_POWER_DEBOUNCE_MS = 3_000L;
    private static final long RAF_SWAP_DEBOUNCE_MS = 10_000L;
    private static final AtomicInteger SERIAL_GEN = new AtomicInteger(1000);
    private static final Object[] SLOT_LOCKS = {new Object(), new Object()};
    private static final int[] DESIRED_IMS_SWITCH = {-1, -1};
    private static final boolean[] SWITCH_WORKER_ACTIVE = {false, false};
    private static final Object DUAL_SIM_LOCK = new Object();
    private static long sLastDdsAssignMs = -100_000L;
    private static int sLastDdsAssignSubId = -1;
    private static final long[] sLastRadioPowerKickMs = {-100_000L, -100_000L};
    private static long sLastRafSwapMs = -100_000L;
    private static final boolean[] sLastDisabledImsSynced = {false, false};
    static volatile ImsSwitchSender sSwitchSender = HwImsShim::sendSetImsSwitchOnce;
    static volatile long sInitialRetryDelayMs = 500L;

    static final class SimSubSlot {
        final int slotId;
        final int subId;
        final boolean uiccAppsEnabled;

        SimSubSlot(int slotId, int subId, boolean uiccAppsEnabled) {
            this.slotId = slotId;
            this.subId = subId;
            this.uiccAppsEnabled = uiccAppsEnabled;
        }
    }

    interface DualSimEnv {
        List<SimSubSlot> getActiveSubscriptions();

        int getDefaultDataSubId();

        boolean isValidSubId(int subId);

        int getSlotIndex(int subId);

        void setDefaultDataSubId(int subId);

        boolean isAirplaneModeOff();

        int getPhoneCount();

        boolean isRadioAvailable(int slotId);

        boolean isRadioOn(int slotId);

        void forceRadioPowerOn(int slotId);

        void resyncImsSwitch(int slotId);

        default void disableImsSwitch(int slotId) {}

        int getCallState();

        String getModemUuid(int slotId);

        int getPhoneRaf(int slotId);

        boolean setRadioCapability(RadioAccessFamily[] rafs);
    }

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
        ensureDualSimCoordinator(context);
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

    public static void ensureDualSimCoordinator(Context context) {
        if (context == null) {
            return;
        }
        final Context appCtx =
                context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (System.getProperties()) {
            if ("true".equals(System.getProperties().getProperty(DUAL_SIM_COORD_PROP))) {
                return;
            }
            System.getProperties().setProperty(DUAL_SIM_COORD_PROP, "true");
        }
        ensureDualLteAndVoWifiState(appCtx);
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        Runnable registerTask =
                () -> {
                    final DualSimEnv env = createLiveDualSimEnv(appCtx);
                    final Runnable runImmediate =
                            () -> {
                                try {
                                    reconcileDualSimState(env, SystemClock.elapsedRealtime());
                                } catch (Throwable t) {
                                    Log.w(TAG, "Dual-SIM immediate reconcile failed", t);
                                }
                            };
                    final Runnable runFollowUp2s =
                            () -> {
                                try {
                                    reconcileDualSimState(env, SystemClock.elapsedRealtime());
                                } catch (Throwable t) {
                                    Log.w(TAG, "Dual-SIM 2s reconcile failed", t);
                                }
                            };
                    final Runnable runFollowUp5s =
                            () -> {
                                try {
                                    reconcileDualSimState(env, SystemClock.elapsedRealtime());
                                } catch (Throwable t) {
                                    Log.w(TAG, "Dual-SIM 5s reconcile failed", t);
                                }
                            };
                    final Runnable scheduleBurst =
                            () -> {
                                mainHandler.removeCallbacks(runImmediate);
                                mainHandler.removeCallbacks(runFollowUp2s);
                                mainHandler.removeCallbacks(runFollowUp5s);
                                mainHandler.post(runImmediate);
                                mainHandler.postDelayed(runFollowUp2s, 2_000L);
                                mainHandler.postDelayed(runFollowUp5s, 5_000L);
                            };
                    try {
                        SubscriptionManager sm = appCtx.getSystemService(SubscriptionManager.class);
                        if (sm != null) {
                            sm.addOnSubscriptionsChangedListener(
                                    new SubscriptionManager.OnSubscriptionsChangedListener() {
                                        @Override
                                        public void onSubscriptionsChanged() {
                                            scheduleBurst.run();
                                        }
                                    });
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed registering OnSubscriptionsChangedListener", t);
                    }
                    try {
                        IntentFilter filter = new IntentFilter();
                        filter.addAction(
                                "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
                        filter.addAction("android.intent.action.RADIO_STATE_CHANGED");
                        filter.addAction("android.intent.action.SIM_STATE_CHANGED");
                        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                        BroadcastReceiver receiver =
                                new BroadcastReceiver() {
                                    @Override
                                    public void onReceive(Context ctx, Intent intent) {
                                        scheduleBurst.run();
                                    }
                                };
                        appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed registering Dual-SIM BroadcastReceiver", t);
                    }
                    scheduleBurst.run();
                };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            registerTask.run();
        } else {
            mainHandler.post(registerTask);
        }
    }

    private static DualSimEnv createLiveDualSimEnv(final Context appCtx) {
        return new DualSimEnv() {
            @Override
            public List<SimSubSlot> getActiveSubscriptions() {
                SubscriptionManager sm = appCtx.getSystemService(SubscriptionManager.class);
                if (sm == null) {
                    return Collections.emptyList();
                }
                List<SubscriptionInfo> infos = sm.getActiveSubscriptionInfoList();
                if (infos == null || infos.isEmpty()) {
                    return Collections.emptyList();
                }
                List<SimSubSlot> out = new ArrayList<>(infos.size());
                for (SubscriptionInfo info : infos) {
                    if (info == null) {
                        continue;
                    }
                    boolean enabled = true;
                    try {
                        enabled = info.areUiccApplicationsEnabled();
                    } catch (Throwable ignored) {
                    }
                    out.add(
                            new SimSubSlot(
                                    info.getSimSlotIndex(), info.getSubscriptionId(), enabled));
                }
                return out;
            }

            @Override
            public int getDefaultDataSubId() {
                return SubscriptionManager.getDefaultDataSubscriptionId();
            }

            @Override
            public boolean isValidSubId(int subId) {
                return SubscriptionManager.isValidSubscriptionId(subId);
            }

            @Override
            public int getSlotIndex(int subId) {
                return SubscriptionManager.getSlotIndex(subId);
            }

            @Override
            public void setDefaultDataSubId(int subId) {
                SubscriptionManager sm = appCtx.getSystemService(SubscriptionManager.class);
                if (sm != null) {
                    Log.i(TAG, "Auto-selecting default data subscription subId=" + subId);
                    sm.setDefaultDataSubId(subId);
                }
            }

            @Override
            public boolean isAirplaneModeOff() {
                try {
                    return Settings.Global.getInt(
                                    appCtx.getContentResolver(),
                                    Settings.Global.AIRPLANE_MODE_ON,
                                    0)
                            == 0;
                } catch (Throwable ignored) {
                    return true;
                }
            }

            @Override
            public int getPhoneCount() {
                Phone[] phones = PhoneFactory.getPhones();
                return phones != null ? phones.length : 0;
            }

            @Override
            public boolean isRadioAvailable(int slotId) {
                Phone phone = PhoneFactory.getPhone(slotId);
                return phone != null && phone.isRadioAvailable();
            }

            @Override
            public boolean isRadioOn(int slotId) {
                Phone phone = PhoneFactory.getPhone(slotId);
                return phone != null && phone.isRadioOn();
            }

            @Override
            public void forceRadioPowerOn(int slotId) {
                Phone phone = PhoneFactory.getPhone(slotId);
                if (phone != null) {
                    Log.i(TAG, "Forcing RADIO_POWER(true) recovery on slot=" + slotId);
                    phone.setRadioPower(true, false, false, true);
                }
            }

            @Override
            public void resyncImsSwitch(int slotId) {
                if (!SystemProperties.getBoolean("ro.config.hw_volte_on", true)) {
                    return;
                }
                if (HwImsManager.isEnhanced4gLteModeSettingEnabledByUser(appCtx, slotId)) {
                    enableModemImsSwitch(slotId);
                } else {
                    disableModemImsSwitch(slotId);
                }
            }

            @Override
            public void disableImsSwitch(int slotId) {
                if (!SystemProperties.getBoolean("ro.config.hw_volte_on", true)) {
                    return;
                }
                disableModemImsSwitch(slotId);
            }

            @Override
            public int getCallState() {
                try {
                    TelephonyManager tm = appCtx.getSystemService(TelephonyManager.class);
                    if (tm != null) {
                        return tm.getCallState();
                    }
                } catch (Throwable ignored) {
                }
                return TelephonyManager.CALL_STATE_IDLE;
            }

            @Override
            public String getModemUuid(int slotId) {
                Phone phone = PhoneFactory.getPhone(slotId);
                if (phone == null) {
                    return "";
                }
                String uuid = phone.getModemUuId();
                return uuid != null ? uuid : "";
            }

            @Override
            public int getPhoneRaf(int slotId) {
                Phone phone = PhoneFactory.getPhone(slotId);
                return phone != null ? phone.getRadioAccessFamily() : RadioAccessFamily.RAF_UNKNOWN;
            }

            @Override
            public boolean setRadioCapability(RadioAccessFamily[] rafs) {
                ProxyController pc = ProxyController.getInstance();
                if (pc == null) {
                    return false;
                }
                boolean accepted = pc.setRadioCapability(rafs);
                Log.i(TAG, "ProxyController.setRadioCapability accepted=" + accepted);
                return accepted;
            }
        };
    }

    static void reconcileDualSimState(DualSimEnv env, long nowMs) {
        if (env == null) {
            return;
        }
        synchronized (DUAL_SIM_LOCK) {
            List<SimSubSlot> subs = env.getActiveSubscriptions();
            List<SimSubSlot> enabledSubs = new ArrayList<>();
            boolean[] slotEnabled = {false, false};
            if (subs != null) {
                for (SimSubSlot s : subs) {
                    if (s == null || (s.slotId != 0 && s.slotId != 1)) {
                        continue;
                    }
                    if (s.uiccAppsEnabled && env.isValidSubId(s.subId)) {
                        enabledSubs.add(s);
                        slotEnabled[s.slotId] = true;
                        sLastDisabledImsSynced[s.slotId] = false;
                    } else if (!s.uiccAppsEnabled && !sLastDisabledImsSynced[s.slotId]) {
                        sLastDisabledImsSynced[s.slotId] = true;
                        env.disableImsSwitch(s.slotId);
                    }
                }
            }

            int ddsSubId = env.getDefaultDataSubId();
            boolean ddsValid = false;
            if (env.isValidSubId(ddsSubId)) {
                for (SimSubSlot s : enabledSubs) {
                    if (s.subId == ddsSubId) {
                        ddsValid = true;
                        break;
                    }
                }
            }
            if (!ddsValid && !enabledSubs.isEmpty()) {
                SimSubSlot chosen = enabledSubs.get(0);
                for (SimSubSlot s : enabledSubs) {
                    if (s.slotId == 0) {
                        chosen = s;
                        break;
                    }
                }
                if (chosen.subId != sLastDdsAssignSubId
                        || (nowMs - sLastDdsAssignMs) >= DDS_ASSIGN_DEBOUNCE_MS) {
                    sLastDdsAssignSubId = chosen.subId;
                    sLastDdsAssignMs = nowMs;
                    env.setDefaultDataSubId(chosen.subId);
                }
            }

            boolean airplaneOff = env.isAirplaneModeOff();
            boolean kickedRadioPower = false;
            if (airplaneOff) {
                for (SimSubSlot s : enabledSubs) {
                    int slot = s.slotId;
                    if (env.isRadioAvailable(slot) && !env.isRadioOn(slot)) {
                        if ((nowMs - sLastRadioPowerKickMs[slot]) >= RADIO_POWER_DEBOUNCE_MS) {
                            sLastRadioPowerKickMs[slot] = nowMs;
                            env.forceRadioPowerOn(slot);
                            env.resyncImsSwitch(slot);
                            kickedRadioPower = true;
                        }
                    }
                }
            }

            if (!airplaneOff || !ddsValid || kickedRadioPower) {
                return;
            }
            int ddsSlot = env.getSlotIndex(ddsSubId);
            if (ddsSlot != 0 && ddsSlot != 1) {
                return;
            }
            int otherSlot = 1 - ddsSlot;
            int phoneCount = env.getPhoneCount();
            if (phoneCount < 2
                    || !env.isRadioAvailable(0)
                    || !env.isRadioAvailable(1)
                    || env.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                return;
            }
            String ddsModem = env.getModemUuid(ddsSlot);
            String otherModem = env.getModemUuid(otherSlot);
            if (!"0".equals(otherModem) || "0".equals(ddsModem)) {
                return;
            }
            int modem0Raf = env.getPhoneRaf(otherSlot);
            int modem1Raf = env.getPhoneRaf(ddsSlot);
            if (modem0Raf == RadioAccessFamily.RAF_UNKNOWN
                    || modem1Raf == RadioAccessFamily.RAF_UNKNOWN
                    || modem0Raf == modem1Raf) {
                return;
            }
            if ((nowMs - sLastRafSwapMs) < RAF_SWAP_DEBOUNCE_MS) {
                return;
            }
            RadioAccessFamily[] rafs = new RadioAccessFamily[phoneCount];
            for (int i = 0; i < phoneCount; i++) {
                if (i == ddsSlot) {
                    rafs[i] = new RadioAccessFamily(ddsSlot, modem0Raf);
                } else if (i == otherSlot) {
                    rafs[i] = new RadioAccessFamily(otherSlot, modem1Raf);
                } else {
                    rafs[i] = new RadioAccessFamily(i, env.getPhoneRaf(i));
                }
            }
            if (env.setRadioCapability(rafs)) {
                sLastRafSwapMs = nowMs;
                for (int slot = 0; slot < 2; slot++) {
                    if (slotEnabled[slot]) {
                        env.resyncImsSwitch(slot);
                    } else {
                        env.disableImsSwitch(slot);
                    }
                }
            }
        }
    }

    static void resetDualSimStateForTest() {
        synchronized (System.getProperties()) {
            System.getProperties().remove(DUAL_SIM_COORD_PROP);
        }
        synchronized (DUAL_SIM_LOCK) {
            sLastDdsAssignMs = -100_000L;
            sLastDdsAssignSubId = -1;
            sLastRadioPowerKickMs[0] = -100_000L;
            sLastRadioPowerKickMs[1] = -100_000L;
            sLastRafSwapMs = -100_000L;
            sLastDisabledImsSynced[0] = false;
            sLastDisabledImsSynced[1] = false;
        }
    }

    public static void enableModemImsSwitch(final int slotId) {
        setModemImsSwitchAsync(slotId, 1);
    }

    public static void disableModemImsSwitch(final int slotId) {
        setModemImsSwitchAsync(slotId, 0);
    }

    static void resetSwitchStateForTest() {
        synchronized (System.getProperties()) {
            System.getProperties().remove(DESIRED_IMS_SWITCH_PROP_PREFIX + "0");
            System.getProperties().remove(DESIRED_IMS_SWITCH_PROP_PREFIX + "1");
        }
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
        synchronized (System.getProperties()) {
            System.getProperties()
                    .setProperty(DESIRED_IMS_SWITCH_PROP_PREFIX + slotId, Integer.toString(state));
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
            IHwBinder binder = HwBinder.getService(HW_RADIO_V2_0, slotService, false);
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
            String sharedVal;
            synchronized (System.getProperties()) {
                sharedVal = System.getProperties().getProperty(DESIRED_IMS_SWITCH_PROP_PREFIX + slotId);
            }
            if ("1".equals(sharedVal)) {
                return true;
            }
            if ("0".equals(sharedVal)) {
                return false;
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

    private static void ensureDualLteAndVoWifiState(Context appCtx) {
        try {
            Method setProp =
                    SystemProperties.class.getMethod("set", String.class, String.class);
            if (!"1".equals(SystemProperties.get("persist.radio.defdualltecap", ""))) {
                setProp.invoke(null, "persist.radio.defdualltecap", "1");
            }
            if (!"1".equals(SystemProperties.get("persist.radio.dualltecap", ""))) {
                setProp.invoke(null, "persist.radio.dualltecap", "1");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to sync persist.radio.defdualltecap/dualltecap", t);
        }
        try {
            if (SystemProperties.getBoolean("ro.config.hw_vowifi", false)) {
                PackageManager pm = appCtx.getPackageManager();
                if (pm != null
                        && pm.getApplicationEnabledSetting("com.hisi.mapcon")
                                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    Log.i(TAG, "Re-enabling com.hisi.mapcon for VoWiFi");
                    pm.setApplicationEnabledSetting(
                            "com.hisi.mapcon",
                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                            0);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to check/re-enable com.hisi.mapcon", t);
        }
    }

    public static PendingIntent getPendingIntentBroadcast(
            Context context, int requestCode, Intent intent, int flags) {
        if ((flags & (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_MUTABLE)) == 0) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    public static PendingIntent getPendingIntentActivity(
            Context context, int requestCode, Intent intent, int flags) {
        if ((flags & (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_MUTABLE)) == 0) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }

    static int resolveDynamicReceiverFlags(IntentFilter filter, String broadcastPermission) {
        if (broadcastPermission != null && !broadcastPermission.isEmpty()) {
            return Context.RECEIVER_EXPORTED;
        }
        if (filter != null) {
            int count = filter.countActions();
            for (int i = 0; i < count; i++) {
                String action = filter.getAction(i);
                if (action != null
                        && (action.startsWith("android.")
                                || action.startsWith("huawei.intent.action.")
                                || action.startsWith("com.huawei."))) {
                    return Context.RECEIVER_EXPORTED;
                }
            }
        }
        return Context.RECEIVER_NOT_EXPORTED;
    }

    public static Intent registerReceiverCompat(
            Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (context == null) {
            return null;
        }
        return context.registerReceiver(
                receiver, filter, resolveDynamicReceiverFlags(filter, null));
    }

    public static Intent registerReceiverCompat(
            Context context,
            BroadcastReceiver receiver,
            IntentFilter filter,
            String broadcastPermission,
            Handler scheduler) {
        if (context == null) {
            return null;
        }
        return context.registerReceiver(
                receiver,
                filter,
                broadcastPermission,
                scheduler,
                resolveDynamicReceiverFlags(filter, broadcastPermission));
    }

    private static final WeakHashMap<PhoneStateListener, Integer> sPhoneStateListenerSubIds =
            new WeakHashMap<>();
    private static final WeakHashMap<PhoneStateListener, Integer> sPhoneStateListenerSlots =
            new WeakHashMap<>();
    private static final WeakHashMap<PhoneStateListener, Integer>
            sPhoneStateListenerRegisteredSubIds = new WeakHashMap<>();

    public static void recordPhoneStateListenerSubId(PhoneStateListener listener, Integer subId) {
        if (listener == null || subId == null) {
            return;
        }
        synchronized (sPhoneStateListenerSubIds) {
            sPhoneStateListenerSubIds.put(listener, subId);
        }
    }

    public static void recordPhoneStateListenerSlot(PhoneStateListener listener, int slotId) {
        if (listener == null || slotId < 0) {
            return;
        }
        synchronized (sPhoneStateListenerSubIds) {
            sPhoneStateListenerSlots.put(listener, slotId);
        }
    }

    public static int resolveSlotIndexForSubId(int subId) {
        return subId >= 0 ? sSlotIndexResolver.applyAsInt(subId) : -1;
    }

    private static Integer extractListenerPhoneIdField(PhoneStateListener listener) {
        for (Class<?> cls = listener.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Field f = cls.getDeclaredField("mPhoneId");
                f.setAccessible(true);
                Object val = f.get(listener);
                if (val instanceof Integer) {
                    return (Integer) val;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static void listenPhoneState(
            TelephonyManager tm, PhoneStateListener listener, int events) {
        if (tm == null || listener == null) {
            return;
        }
        Integer subId = null;
        synchronized (sPhoneStateListenerSubIds) {
            if (events == 0) {
                subId = sPhoneStateListenerRegisteredSubIds.remove(listener);
            }
            if (subId == null || subId.intValue() < 0) {
                subId = sPhoneStateListenerSubIds.get(listener);
            }
            if (subId == null || subId.intValue() < 0) {
                Integer slotId = sPhoneStateListenerSlots.get(listener);
                if (slotId == null || slotId.intValue() < 0) {
                    slotId = extractListenerPhoneIdField(listener);
                }
                if (slotId != null && slotId.intValue() >= 0) {
                    int resolved = sSubscriptionIdResolver.applyAsInt(slotId.intValue());
                    if (resolved >= 0) {
                        subId = resolved;
                    }
                }
            }
            if (events != 0 && subId != null && subId.intValue() >= 0) {
                sPhoneStateListenerRegisteredSubIds.put(listener, subId);
            }
        }
        TelephonyManager targetTm = tm;
        if (subId != null && subId.intValue() >= 0) {
            TelephonyManager subTm = tm.createForSubscriptionId(subId.intValue());
            if (subTm != null) {
                targetTm = subTm;
            }
        }
        targetTm.listen(listener, events);
    }

    static volatile IntUnaryOperator sSlotIndexResolver = SubscriptionManager::getSlotIndex;
    static volatile IntUnaryOperator sSubscriptionIdResolver =
            SubscriptionManager::getSubscriptionId;

    private static String readSimIsoCountryPropertyForPhone(int phoneId) {
        if (phoneId < 0) {
            return "";
        }
        String prop = SystemProperties.get("gsm.sim.operator.iso-country", "");
        if (prop == null || prop.isEmpty()) {
            return "";
        }
        String[] values = prop.split(",", -1);
        if (phoneId >= values.length || values[phoneId] == null) {
            return "";
        }
        return values[phoneId].trim();
    }

    public static String getSimCountryIsoForSubId(TelephonyManager tm, int subId) {
        try {
            if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
                if (tm == null) {
                    return "";
                }
                String iso = tm.getSimCountryIso();
                return iso != null ? iso : "";
            }
            if (subId < 0) {
                return "";
            }
            if (tm != null) {
                TelephonyManager subTm = tm.createForSubscriptionId(subId);
                if (subTm != null) {
                    String iso = subTm.getSimCountryIso();
                    if (iso != null && !iso.isEmpty()) {
                        return iso;
                    }
                }
            }
            int slotIndex = sSlotIndexResolver.applyAsInt(subId);
            return readSimIsoCountryPropertyForPhone(slotIndex);
        } catch (Throwable t) {
            Log.w(TAG, "getSimCountryIsoForSubId failed for subId=" + subId, t);
            return "";
        }
    }

    public static String getSimCountryIsoForPhone(TelephonyManager tm, int phoneId) {
        if (phoneId < 0) {
            return "";
        }
        try {
            int subId = sSubscriptionIdResolver.applyAsInt(phoneId);
            if (subId >= 0 && tm != null) {
                TelephonyManager subTm = tm.createForSubscriptionId(subId);
                if (subTm != null) {
                    String iso = subTm.getSimCountryIso();
                    if (iso != null && !iso.isEmpty()) {
                        return iso;
                    }
                }
            }
            return readSimIsoCountryPropertyForPhone(phoneId);
        } catch (Throwable t) {
            Log.w(TAG, "getSimCountryIsoForPhone failed for phoneId=" + phoneId, t);
            return "";
        }
    }

    public static String getNetworkCountryIsoForSubId(TelephonyManager tm, int subId) {
        if (tm == null) {
            return "";
        }
        try {
            if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
                String iso = tm.getNetworkCountryIso();
                return iso != null ? iso : "";
            }
            int slotIndex = sSlotIndexResolver.applyAsInt(subId);
            if (slotIndex < 0) {
                return "";
            }
            String iso = tm.getNetworkCountryIso(slotIndex);
            return iso != null ? iso : "";
        } catch (Throwable t) {
            Log.w(TAG, "getNetworkCountryIsoForSubId failed for subId=" + subId, t);
            return "";
        }
    }

    public static void requestLocationUpdatesSafe(
            LocationManager lm,
            String provider,
            long minTimeMs,
            float minDistanceM,
            LocationListener listener) {
        if (lm == null || provider == null || provider.isEmpty() || listener == null) {
            return;
        }
        try {
            List<String> providers = lm.getAllProviders();
            if (providers == null || !providers.contains(provider)) {
                return;
            }
            lm.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener);
        } catch (Throwable t) {
            Log.w(TAG, "requestLocationUpdatesSafe skipped for provider=" + provider, t);
        }
    }

    public static Location getLastKnownLocationSafe(LocationManager lm, String provider) {
        if (lm == null || provider == null || provider.isEmpty()) {
            return null;
        }
        try {
            List<String> providers = lm.getAllProviders();
            if (providers == null || !providers.contains(provider)) {
                return null;
            }
            return lm.getLastKnownLocation(provider);
        } catch (Throwable t) {
            Log.w(TAG, "getLastKnownLocationSafe skipped for provider=" + provider, t);
            return null;
        }
    }

    public static int extractCarrierConfigSlotId(Intent intent) {
        if (intent == null) {
            return -1;
        }
        if (intent.hasExtra(CarrierConfigManager.EXTRA_SLOT_INDEX)) {
            return intent.getIntExtra(CarrierConfigManager.EXTRA_SLOT_INDEX, -1);
        }
        if (intent.hasExtra("phone")) {
            return intent.getIntExtra("phone", -1);
        }
        return -1;
    }

    public static boolean isCarrierConfigReadyForSlot(Context context, int slotId) {
        if (context == null || slotId < 0) {
            return false;
        }
        try {
            int subId = sSubscriptionIdResolver.applyAsInt(slotId);
            if (subId < 0 || subId == Integer.MAX_VALUE) {
                return false;
            }
            CarrierConfigManager ccm = context.getSystemService(CarrierConfigManager.class);
            if (ccm == null) {
                return false;
            }
            PersistableBundle cfg = ccm.getConfigForSubId(subId);
            if (cfg == null) {
                return false;
            }
            if (CarrierConfigManager.isConfigForIdentifiedCarrier(cfg)) {
                return true;
            }
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            return tm != null && tm.getSimState(slotId) == TelephonyManager.SIM_STATE_READY;
        } catch (Throwable t) {
            Log.w(TAG, "isCarrierConfigReadyForSlot failed for slotId=" + slotId, t);
            return false;
        }
    }

    public static boolean isCarrierConfigChangeLoaded(Context context, int slotId, Intent intent) {
        if (intent != null) {
            if (intent.hasExtra("carrierConfigChangeState")) {
                return intent.getIntExtra("carrierConfigChangeState", 0) == 1;
            }
            int subId = intent.getIntExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, -1);
            if (subId < 0 || subId == Integer.MAX_VALUE) {
                subId = intent.getIntExtra("subscription", -1);
            }
            if (subId >= 0 && subId != Integer.MAX_VALUE) {
                return true;
            }
            if (intent.hasExtra(CarrierConfigManager.EXTRA_SLOT_INDEX)) {
                return false;
            }
        }
        return isCarrierConfigReadyForSlot(context, slotId);
    }

    public static void notifyRegistrationTechUpdate(
            ImsRegistrationImplBase regImpl, int regState, int accessTech) {
        if (regImpl == null) {
            return;
        }
        try {
            if (accessTech == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN) {
                regImpl.onRegistered(ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
            } else if (accessTech == ImsRegistrationImplBase.REGISTRATION_TECH_LTE
                    && regState == 1) {
                regImpl.onRegistered(ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "notifyRegistrationTechUpdate failed tech=" + accessTech, t);
        }
    }
}
