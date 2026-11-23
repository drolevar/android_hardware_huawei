/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.telephony;

import android.os.SystemProperties;

public class HwTelephonyManagerInner {
    private static final HwTelephonyManagerInner INSTANCE = new HwTelephonyManagerInner();

    public static HwTelephonyManagerInner getDefault() {
        return INSTANCE;
    }

    public int getDefault4GSlotId() {
        return HwTelephonyManager.getDefault().getDefault4GSlotId();
    }

    public boolean isWifiCallingAvailable(int slotId) {
        return SystemProperties.getBoolean("ro.vendor.config.hw_vowifi", false);
    }
}
