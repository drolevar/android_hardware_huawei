/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.telephony.ServiceState;

public abstract class Phone extends Handler implements PhoneInternalInterface {
    public CommandsInterface mCi;

    public Context getContext() {
        return null;
    }

    public int getPhoneType() {
        return 1;
    }

    public Phone getImsPhone() {
        return null;
    }

    public int getImsRegistrationTech() {
        return -1;
    }

    public void registerForNewRingingConnection(Handler h, int what, Object obj) {}

    @Override
    public ServiceState getServiceState() {
        return null;
    }

    @Override
    public void getCallForwardingOption(int commandInterfaceCFReason, Message onComplete) {}

    @Override
    public void getCallForwardingOption(
            int commandInterfaceCFReason, int serviceClass, Message onComplete) {}

    @Override
    public void setCallForwardingOption(
            int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int serviceClass,
            int timerSeconds,
            Message onComplete) {}

    @Override
    public void getCallBarring(
            String facility, String password, Message onComplete, int serviceClass) {}

    @Override
    public void setCallBarring(
            String facility,
            boolean lockState,
            String password,
            Message onComplete,
            int serviceClass) {}
}
