/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.ims;

import android.content.Context;

public class ImsManager {
    public static ImsManager getInstance(Context context, int phoneId) {
        return null;
    }

    public boolean isEnhanced4gLteModeSettingEnabledByUser() {
        return true;
    }

    public boolean isNonTtyOrTtyOnVolteEnabled() {
        return true;
    }

    public boolean isVtEnabledByPlatform() {
        return false;
    }

    public boolean isVtEnabledByUser() {
        return false;
    }

    public boolean isWfcEnabledByPlatform() {
        return false;
    }

    public boolean isWfcEnabledByUser() {
        return false;
    }

    public int getWfcMode(boolean roaming) {
        return 1;
    }
}
