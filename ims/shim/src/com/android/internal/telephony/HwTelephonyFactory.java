/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

import android.os.Bundle;
import com.android.internal.telephony.imsphone.ImsPhone;

public class HwTelephonyFactory {
    private static final HwChrServiceManager CHR_SERVICE_MGR =
            new HwChrServiceManager() {
                @Override
                public void reportCallException(
                        String appName, int subId, int callType, String params) {}
            };

    private static final HwTelephonyChrManager TELEPHONY_CHR_MGR =
            new HwTelephonyChrManager() {
                @Override
                public void sendTelephonyChrBroadcast(Bundle data) {}
            };

    private static final HwVolteChrManager VOLTE_CHR_MGR =
            new HwVolteChrManager() {
                @Override
                public void notifyCSRedial() {}

                @Override
                public void notifySrvccState(int state) {}

                @Override
                public void setRemoteCauseCode(int causeCode) {}

                @Override
                public void triggerHangupFailedEvent() {}

                @Override
                public boolean triggerImsRegFailEvent(ImsPhone imsPhone) {
                    return false;
                }

                @Override
                public void triggerMtCallFailEvent(
                        long ringTime, long inviteTime, int failType, int failCause) {}

                @Override
                public void triggerNoServiceDuringCallEvent(
                        Call fgCall, Call bgCall, Call ringingCall) {}

                @Override
                public void updateMtCallLog(int event) {}
            };

    public static HwChrServiceManager getHwChrServiceManager() {
        return CHR_SERVICE_MGR;
    }

    public static HwTelephonyChrManager getHwTelephonyChrManager() {
        return TELEPHONY_CHR_MGR;
    }

    public static HwVolteChrManager getHwVolteChrManager() {
        return VOLTE_CHR_MGR;
    }
}
