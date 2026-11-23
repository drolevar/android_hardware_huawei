/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.telephony.ServiceState;

public interface PhoneInternalInterface {
    enum SuppService {
        UNKNOWN, SWITCH, SEPARATE, TRANSFER, CONFERENCE, REJECT, HANGUP, RESUME, HOLD
    }

    ServiceState getServiceState();
    void getCallForwardingOption(int commandInterfaceCFReason, Message onComplete);
    void getCallForwardingOption(int commandInterfaceCFReason, int serviceClass, Message onComplete);
    void setCallForwardingOption(
            int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int serviceClass,
            int timerSeconds,
            Message onComplete);
    void getCallBarring(String facility, String password, Message onComplete, int serviceClass);
    void setCallBarring(
            String facility, boolean lockState, String password, Message onComplete, int serviceClass);
}
