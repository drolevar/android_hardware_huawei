/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.ims.shim;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkProvider;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.net.wifi.WifiInfo;
import android.os.Messenger;
import com.huawei.ims.HwImsShim;

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
}
