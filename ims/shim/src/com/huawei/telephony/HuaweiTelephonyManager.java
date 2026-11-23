/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.telephony;

import android.telephony.HwTelephonyManager;

public class HuaweiTelephonyManager {
    private static final HuaweiTelephonyManager INSTANCE = new HuaweiTelephonyManager();

    public static HuaweiTelephonyManager getDefault() {
        return INSTANCE;
    }

    public int getDefault4GSlotId() {
        return HwTelephonyManager.getDefault().getDefault4GSlotId();
    }
}
