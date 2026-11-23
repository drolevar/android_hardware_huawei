/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony.dataconnection;

import android.telephony.AccessNetworkConstants;

public class TransportManager {
    public static final TransportManager INSTANCE = new TransportManager();

    public int[] getAvailableTransports() {
        return new int[] {
            AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
            AccessNetworkConstants.TRANSPORT_TYPE_WLAN
        };
    }
}
