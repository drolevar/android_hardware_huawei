/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

import android.telephony.RadioAccessFamily;

public class ProxyController {
    public static ProxyController getInstance() {
        return null;
    }

    public int getRadioAccessFamily(int phoneId) {
        return RadioAccessFamily.RAF_UNKNOWN;
    }

    public boolean setRadioCapability(RadioAccessFamily[] rafs) {
        return false;
    }
}
