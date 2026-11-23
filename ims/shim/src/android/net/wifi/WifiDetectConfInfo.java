/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.net.wifi;

import android.os.Parcel;
import android.os.Parcelable;

public class WifiDetectConfInfo implements Parcelable {
    public int mWifiDetectMode;
    public int mThreshold;
    public int mEnvalueCount;

    public WifiDetectConfInfo() {}

    public WifiDetectConfInfo(int detectMode, int threshold, int envalueCount) {
        this.mWifiDetectMode = detectMode;
        this.mThreshold = threshold;
        this.mEnvalueCount = envalueCount;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mWifiDetectMode);
        dest.writeInt(mThreshold);
        dest.writeInt(mEnvalueCount);
    }

    public static final Creator<WifiDetectConfInfo> CREATOR =
            new Creator<WifiDetectConfInfo>() {
                @Override
                public WifiDetectConfInfo createFromParcel(Parcel in) {
                    return new WifiDetectConfInfo(in.readInt(), in.readInt(), in.readInt());
                }

                @Override
                public WifiDetectConfInfo[] newArray(int size) {
                    return new WifiDetectConfInfo[size];
                }
            };
}
