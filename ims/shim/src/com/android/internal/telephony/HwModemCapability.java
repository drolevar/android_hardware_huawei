/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

import android.os.SystemProperties;

public class HwModemCapability {
    private static final int MODEM_CAP_SPEECH_CODEC_ADJUST = 11;

    public static boolean isCapabilitySupport(int capability) {
        if (capability == MODEM_CAP_SPEECH_CODEC_ADJUST) {
            return false;
        }
        String caps = SystemProperties.get("persist.radio.modem.cap", "");
        if (!caps.isEmpty() && capability >= 0) {
            int hexIdx = capability / 4;
            int bitIdx = 3 - (capability % 4);
            if (hexIdx < caps.length()) {
                int nibble = Character.digit(caps.charAt(hexIdx), 16);
                if (nibble >= 0) {
                    return ((nibble >> bitIdx) & 1) == 1;
                }
            }
        }
        return false;
    }
}
