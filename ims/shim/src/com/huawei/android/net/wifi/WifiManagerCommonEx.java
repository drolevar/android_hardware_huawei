/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.android.net.wifi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

public final class WifiManagerCommonEx {
    private WifiManagerCommonEx() {}

    public static boolean getHwMeteredHint(Context context) {
        if (context == null) {
            return false;
        }
        try {
            WifiManager wm = context.getSystemService(WifiManager.class);
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            Network net = wm != null ? wm.getCurrentNetwork() : null;
            NetworkCapabilities nc = (cm != null && net != null) ? cm.getNetworkCapabilities(net) : null;
            if (nc != null) {
                return !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
