/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.net.wifi.HwQoE;

import android.os.Parcel;
import android.os.Parcelable;

public class HwQoEQualityInfo implements Parcelable {
    public long mSpeed;
    public long Za;
    public long mOtaRtt;
    public int _a;

    public HwQoEQualityInfo() {}

    public HwQoEQualityInfo(long speed, long za, long otaRtt, int state) {
        this.mSpeed = speed;
        this.Za = za;
        this.mOtaRtt = otaRtt;
        this._a = state;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mSpeed);
        dest.writeLong(Za);
        dest.writeLong(mOtaRtt);
        dest.writeInt(_a);
    }

    public static final Creator<HwQoEQualityInfo> CREATOR =
            new Creator<HwQoEQualityInfo>() {
                @Override
                public HwQoEQualityInfo createFromParcel(Parcel in) {
                    return new HwQoEQualityInfo(
                            in.readLong(), in.readLong(), in.readLong(), in.readInt());
                }

                @Override
                public HwQoEQualityInfo[] newArray(int size) {
                    return new HwQoEQualityInfo[size];
                }
            };
}
