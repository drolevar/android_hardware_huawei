/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

public class PhoneFactory {
    public static Phone getDefaultPhone() {
        return null;
    }

    public static Phone getPhone(int phoneId) {
        return null;
    }

    public static Phone[] getPhones() {
        return new Phone[0];
    }
}
