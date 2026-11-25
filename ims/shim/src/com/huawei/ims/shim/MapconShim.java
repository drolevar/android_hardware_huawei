/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.ims.shim;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkProvider;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Messenger;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import com.huawei.ims.HwImsShim;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class MapconShim {
    private MapconShim() {}

    public static void onServiceCreated(Context context) {
        HwImsShim.ensureDualSimCoordinator(context);
    }

    public static int registerNetworkFactory(
            ConnectivityManager cm, Messenger messenger, String name) {
        LegacyNetworkFactory factory = LegacyNetworkFactory.findByMessenger(messenger);
        if (factory == null) {
            return NetworkProvider.ID_NONE;
        }
        return factory.registerProvider(cm, name);
    }

    public static void unregisterNetworkFactory(ConnectivityManager cm, Messenger messenger) {
        LegacyNetworkFactory factory = LegacyNetworkFactory.findByMessenger(messenger);
        if (factory != null) {
            factory.unregisterProvider(cm);
        }
    }

    public static int getWifiSnr(WifiInfo wifiInfo) {
        if (wifiInfo == null) {
            return -1;
        }
        int rssi = wifiInfo.getRssi();
        if (rssi <= -127 || rssi >= 0) {
            return -1;
        }
        // Derive a stable SNR estimate relative to a -95 dBm noise floor for VoWiFi QoE.
        return Math.max(0, rssi + 95);
    }

    public static int parseSubIdFromSpecifier(NetworkSpecifier specifier) {
        if (specifier == null) {
            return -1;
        }
        if (specifier instanceof TelephonyNetworkSpecifier) {
            return ((TelephonyNetworkSpecifier) specifier).getSubscriptionId();
        }
        if ("android.net.StringNetworkSpecifier".equals(specifier.getClass().getName())) {
            try {
                Object raw = specifier.getClass().getField("specifier").get(specifier);
                if (raw instanceof String) {
                    return Integer.parseInt((String) raw);
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    public static NetworkSpecifier createSubIdSpecifier(int subId) {
        try {
            return new TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int extractSimSlotIndex(Intent intent, int defaultValue) {
        if (intent == null) {
            return defaultValue;
        }
        String[] slotKeys = {
            "android.telephony.extra.SLOT_INDEX",
            "phone",
            "slot",
        };
        for (String key : slotKeys) {
            if (intent.hasExtra(key)) {
                int slot = intent.getIntExtra(key, -1);
                if (slot >= 0) {
                    return slot;
                }
            }
        }
        String[] subKeys = {
            "android.telephony.extra.SUBSCRIPTION_INDEX",
            "subscription",
        };
        for (String key : subKeys) {
            if (intent.hasExtra(key)) {
                int subId = intent.getIntExtra(key, -1);
                int slot = HwImsShim.resolveSlotIndexForSubId(subId);
                if (slot >= 0) {
                    return slot;
                }
            }
        }
        return defaultValue;
    }

    public static void prepareSlotForInitialLoadedReplay(
            int[] be, boolean[] ie, int slot, String simOperatorNumeric) {
        if (be != null && slot >= 0 && slot < be.length && be[slot] == 5) {
            be[slot] = 0;
            if (ie != null
                    && slot < ie.length
                    && simOperatorNumeric != null
                    && !simOperatorNumeric.isEmpty()) {
                ie[slot] = true;
            }
        }
    }

    public static void syncInitialSimStates(
            final Context context, final Object telephonyTracker) {
        if (context == null || telephonyTracker == null) {
            return;
        }
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(
                () -> {
                    try {
                        Class<?> smMgrCls =
                                Class.forName(
                                        "com.hisi.mapcon.l",
                                        false,
                                        telephonyTracker.getClass().getClassLoader());
                        Object smMgr = smMgrCls.getDeclaredMethod("getInstance").invoke(null);
                        if (!(smMgr instanceof Handler)) {
                            return;
                        }
                        ((Handler) smMgr)
                                .post(
                                        () ->
                                                mainHandler.post(
                                                        () ->
                                                                replayInitialSimStatesOnMain(
                                                                        context,
                                                                        telephonyTracker,
                                                                        smMgrCls)));
                    } catch (Throwable t) {
                        Log.w("MapconShim", "syncInitialSimStates schedule failed", t);
                    }
                });
    }

    private static void replayInitialSimStatesOnMain(
            Context context, Object telephonyTracker, Class<?> smMgrCls) {
        try {
            if (telephonyTracker.getClass().getDeclaredMethod("getInstance").invoke(null)
                    != telephonyTracker) {
                return;
            }
            if (smMgrCls.getDeclaredMethod("getInstance").invoke(null) == null) {
                return;
            }
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            if (tm == null) {
                return;
            }
            Field beField = telephonyTracker.getClass().getDeclaredField("be");
            beField.setAccessible(true);
            int[] be = (int[]) beField.get(telephonyTracker);
            Field ieField = telephonyTracker.getClass().getDeclaredField("ie");
            ieField.setAccessible(true);
            boolean[] ie = (boolean[]) ieField.get(telephonyTracker);
            Method handleSim =
                    telephonyTracker.getClass().getDeclaredMethod("e", Intent.class);
            handleSim.setAccessible(true);
            for (int slot = 0; slot < 2; slot++) {
                int simState = tm.getSimState(slot);
                int subId = SubscriptionManager.getSubscriptionId(slot);
                String ss = null;
                if ((simState == TelephonyManager.SIM_STATE_READY
                                || simState == TelephonyManager.SIM_STATE_LOADED)
                        && SubscriptionManager.isValidSubscriptionId(subId)) {
                    TelephonyManager subTm = tm.createForSubscriptionId(subId);
                    String simOperatorNumeric = subTm != null ? subTm.getSimOperator() : null;
                    prepareSlotForInitialLoadedReplay(be, ie, slot, simOperatorNumeric);
                    ss = "LOADED";
                } else if (simState == TelephonyManager.SIM_STATE_ABSENT) {
                    ss = "ABSENT";
                } else if (simState == TelephonyManager.SIM_STATE_NOT_READY) {
                    ss = "NOT_READY";
                }
                if (ss != null) {
                    Intent simIntent = new Intent("android.intent.action.SIM_STATE_CHANGED");
                    simIntent.putExtra("ss", ss);
                    simIntent.putExtra("phone", slot);
                    simIntent.putExtra("android.telephony.extra.SLOT_INDEX", slot);
                    handleSim.invoke(telephonyTracker, simIntent);
                }
            }
        } catch (Throwable t) {
            Log.w("MapconShim", "replayInitialSimStatesOnMain failed", t);
        }
    }

    public static void recordPhoneStateListenerForSlot(PhoneStateListener listener, int slotId) {
        HwImsShim.recordPhoneStateListenerSlot(listener, slotId);
    }

    public static int extractImsStatePhoneId(Bundle extras) {
        if (extras == null) {
            return -1;
        }
        String[] slotKeys = {
            "slot",
            "android.telephony.extra.SLOT_INDEX",
            "phone",
        };
        for (String key : slotKeys) {
            if (extras.containsKey(key)) {
                int val = extras.getInt(key, -1);
                if (val == 0 || val == 1) {
                    return val;
                }
            }
        }
        if (extras.containsKey("subId")) {
            int rawSubId = extras.getInt("subId", -1);
            int phoneId = HwImsShim.resolveSlotIndexForSubId(rawSubId);
            if (phoneId == 0 || phoneId == 1) {
                return phoneId;
            }
            if (rawSubId == 0 || rawSubId == 1) {
                return rawSubId;
            }
        }
        return -1;
    }
}
