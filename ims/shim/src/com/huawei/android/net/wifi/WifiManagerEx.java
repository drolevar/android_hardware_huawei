/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.android.net.wifi;

import android.content.Context;
import android.net.TrafficStats;
import android.net.wifi.HwQoE.HwQoEQualityInfo;
import android.net.wifi.HwQoE.c;
import android.net.wifi.WifiDetectConfInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Message;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.huawei.ims.shim.VoWifiSignalDetectInterruptCallback;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.LongSupplier;

/**
 * Static Huawei {@code WifiManagerEx} compatibility methods invoked reflectively by {@code Mapcon}
 * ({@code WifiAdapter}, {@code method.invoke(null, ...)}).
 */
public final class WifiManagerEx {
    private static final int VOWIFI_SIGNAL_RAW_BYTES = 60;
    private static final String FALLBACK_VALID_BSSID = "020000000000";

    private WifiManagerEx() {}

    static volatile LongSupplier sWlan0TxPacketsSupplier =
            () -> TrafficStats.getTxPackets("wlan0");

    private static WifiManager resolveWifiManager() {
        try {
            Phone defPhone = PhoneFactory.getDefaultPhone();
            Context ctx = defPhone != null ? defPhone.getContext() : null;
            if (ctx != null) {
                return ctx.getSystemService(WifiManager.class);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static long readWlan0TxPackets() {
        try {
            long tx = sWlan0TxPacketsSupplier.getAsLong();
            if (tx > 0L) {
                return tx;
            }
        } catch (Throwable ignored) {
        }
        return 1L;
    }

    /**
     * Returns the 60-byte little-endian {@code WifiSignalInfo} prefix expected by
     * {@code VoWifiQoeService} (which inserts 4-byte {@code mWifiSnr} at offset 40 to form the
     * 64-byte struct validated by {@code libvowifiqoe.so} {@code ProcessSignalInfoInd} /
     * {@code CheckSignalInfoInvalid}).
     */
    public static byte[] fetchWifiSignalInfoForVoWiFi() {
        int rssi = -65;
        int linkSpeed = 72;
        int frequency = 5180;
        String bssidHex = FALLBACK_VALID_BSSID;

        WifiManager wm = resolveWifiManager();
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        if (info != null) {
            int curRssi = info.getRssi();
            if (curRssi < 0 && curRssi > -127) {
                rssi = curRssi;
            }
            if (info.getLinkSpeed() > 0) {
                linkSpeed = info.getLinkSpeed();
            }
            if (info.getFrequency() > 0) {
                frequency = info.getFrequency();
            }
            String rawBssid = info.getBSSID();
            if (rawBssid != null) {
                String stripped = rawBssid.replace(":", "").trim();
                if (stripped.length() == 12 && !"000000000000".equals(stripped)) {
                    bssidHex = stripped;
                }
            }
        }

        long txPackets = readWlan0TxPackets();
        ByteBuffer buf = ByteBuffer.allocate(VOWIFI_SIGNAL_RAW_BYTES);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, rssi);
        buf.putInt(4, 0);
        buf.putInt(8, 0);
        buf.putInt(12, linkSpeed);
        buf.putInt(16, 0);
        buf.putInt(20, frequency);
        buf.putLong(24, txPackets);
        buf.putLong(32, 0L);
        byte[] ascii = bssidHex.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int copyLen = Math.min(ascii.length, 15);
        for (int i = 0; i < copyLen; i++) {
            buf.put(40 + i, ascii[i]);
        }
        return buf.array();
    }

    public static boolean isSupportVoWifiDetect() {
        return false;
    }

    public static boolean registerVoWifiSignalDetectInterrupt(
            WifiManager wm, VoWifiSignalDetectInterruptCallback callback) {
        return false;
    }

    public static boolean setVoWifiDetectMode(WifiManager wm, WifiDetectConfInfo info) {
        return false;
    }

    public static boolean setVoWifiDetectPeriod(int period) {
        return false;
    }

    public static void startPacketKeepalive(Message msg) {}

    public static void stopPacketKeepalive(Message msg) {}

    public static boolean updateVoWifiStatus(int state) {
        return true;
    }

    public static boolean startHwQoeMonitor(int monitorType, int period, c callback) {
        return true;
    }

    public static boolean stopHwQoeMonitor(int monitorType) {
        return true;
    }

    public static boolean evaluateNetworkQuality(c callback) {
        if (callback != null) {
            try {
                callback.a(true, new HwQoEQualityInfo(50000L, 50000L, 15L, 1));
            } catch (Throwable ignored) {
            }
        }
        return true;
    }
}
