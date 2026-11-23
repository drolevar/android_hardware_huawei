/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.net.wifi.HwQoE;

import android.os.IInterface;
import android.os.RemoteException;

public interface c extends IInterface {
    void a(boolean result, HwQoEQualityInfo info) throws RemoteException;

    void g(int status) throws RemoteException;
}
