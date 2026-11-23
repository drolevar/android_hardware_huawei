/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

import android.telephony.SubscriptionManager;

public class SubscriptionController {
    private static final SubscriptionController INSTANCE = new SubscriptionController();

    public static SubscriptionController getInstance() {
        return INSTANCE;
    }

    public int[] getSubId(int slotIndex) {
        try {
            Phone defPhone = PhoneFactory.getDefaultPhone();
            SubscriptionManager sm =
                    (defPhone != null && defPhone.getContext() != null)
                            ? defPhone.getContext().getSystemService(SubscriptionManager.class)
                            : null;
            int[] subIds = sm != null ? sm.getSubscriptionIds(slotIndex) : null;
            if (subIds != null && subIds.length > 0) {
                return subIds;
            }
        } catch (Throwable ignored) {
        }
        return new int[] {SubscriptionManager.INVALID_SUBSCRIPTION_ID};
    }
}
