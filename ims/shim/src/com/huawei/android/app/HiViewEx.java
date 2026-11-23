/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.android.app;

public class HiViewEx {
    public static HiEventEx byPair(int eventId, String key, int value) {
        return new HiEventEx();
    }

    public static void report(HiEventEx event) {}
}
