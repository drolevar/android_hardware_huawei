/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

public interface HwChrServiceManager {
    void reportCallException(String appName, int subId, int callType, String params);
}
