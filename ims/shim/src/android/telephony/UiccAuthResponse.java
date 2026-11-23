/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.telephony;

public class UiccAuthResponse {
    public static final int AUTH_RESP_SUCCESS = 0;
    public static final int AUTH_RESP_FAIL = 1;
    public static final int AUTH_RESP_SYNC_FAIL = 2;
    public static final int AUTH_RESP_UNSUPPORTED = 3;

    public static class UiccAuthResponseData {
        public int present;
        public byte[] data;
        public int len;

        public UiccAuthResponseData() {
            this.present = 0;
            this.data = new byte[0];
            this.len = 0;
        }

        public UiccAuthResponseData(int present, byte[] data) {
            this.present = present;
            this.data = data != null ? data.clone() : new byte[0];
            this.len = this.data.length;
        }
    }

    public static class UiccAuthChallenge {
        public UiccAuthResponseData mResData = new UiccAuthResponseData();
        public UiccAuthResponseData mIkData = new UiccAuthResponseData();
        public UiccAuthResponseData mCkData = new UiccAuthResponseData();
    }

    public int mResult;
    public UiccAuthChallenge mUiccAuthChallenge = new UiccAuthChallenge();
    public UiccAuthResponseData mUiccAuthSyncFail = new UiccAuthResponseData();

    public UiccAuthResponse() {
        this.mResult = AUTH_RESP_FAIL;
    }

    public int getResult() {
        return mResult;
    }

    public UiccAuthChallenge getAuthChallenge() {
        return mUiccAuthChallenge;
    }

    public UiccAuthResponseData getAuthSyncFail() {
        return mUiccAuthSyncFail;
    }
}
