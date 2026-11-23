/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

import android.os.Bundle;

public interface HwTelephonyChrManager {
    void sendTelephonyChrBroadcast(Bundle data);
}
