/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony.imsphone;

import android.os.Message;

public interface IHwImsPhoneEx {
    Message popUtMessage(int id);
    void setIsBusy(boolean busy);
}
