/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony;

import android.os.Bundle;
import android.os.IInterface;
import android.os.RemoteException;

public interface IPhoneCallback extends IInterface {
    void onCallback1(int param1) throws RemoteException;

    void onCallback2(int param1, int param2) throws RemoteException;

    void onCallback3(int param1, int param2, Bundle param3) throws RemoteException;
}
