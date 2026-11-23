/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

import com.android.internal.telephony.imsphone.ImsPhone;

public interface HwVolteChrManager {
    void notifyCSRedial();
    void notifySrvccState(int state);
    void setRemoteCauseCode(int causeCode);
    void triggerHangupFailedEvent();
    boolean triggerImsRegFailEvent(ImsPhone imsPhone);
    void triggerMtCallFailEvent(long ringTime, long inviteTime, int failType, int failCause);
    void triggerNoServiceDuringCallEvent(Call fgCall, Call bgCall, Call ringingCall);
    void updateMtCallLog(int event);
}
