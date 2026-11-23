/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;

public class RadioAccessFamily implements Parcelable {
    public static final int RAF_UNKNOWN = 0;

    private final int mPhoneId;
    private final int mRadioAccessFamily;

    public RadioAccessFamily(int phoneId, int radioAccessFamily) {
        mPhoneId = phoneId;
        mRadioAccessFamily = radioAccessFamily;
    }

    public int getPhoneId() {
        return mPhoneId;
    }

    public int getRadioAccessFamily() {
        return mRadioAccessFamily;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel outParcel, int flags) {
        outParcel.writeInt(mPhoneId);
        outParcel.writeInt(mRadioAccessFamily);
    }

    public static final Creator<RadioAccessFamily> CREATOR =
            new Creator<RadioAccessFamily>() {
                @Override
                public RadioAccessFamily createFromParcel(Parcel in) {
                    return new RadioAccessFamily(in.readInt(), in.readInt());
                }

                @Override
                public RadioAccessFamily[] newArray(int size) {
                    return new RadioAccessFamily[size];
                }
            };
}
