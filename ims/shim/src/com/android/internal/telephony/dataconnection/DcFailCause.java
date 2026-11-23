/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.telephony.dataconnection;

import android.telephony.DataFailCause;

public enum DcFailCause {
    NONE(DataFailCause.NONE),
    OPERATOR_BARRED(DataFailCause.OPERATOR_BARRED),
    MISSING_UNKNOWN_APN(DataFailCause.MISSING_UNKNOWN_APN),
    UNKNOWN_PDP_ADDRESS_TYPE(DataFailCause.UNKNOWN_PDP_ADDRESS_TYPE),
    USER_AUTHENTICATION(DataFailCause.USER_AUTHENTICATION),
    ACTIVATION_REJECT_GGSN(DataFailCause.ACTIVATION_REJECT_GGSN),
    ACTIVATION_REJECT_UNSPECIFIED(DataFailCause.ACTIVATION_REJECT_UNSPECIFIED),
    SERVICE_OPTION_NOT_SUPPORTED(DataFailCause.SERVICE_OPTION_NOT_SUPPORTED),
    SERVICE_OPTION_NOT_SUBSCRIBED(DataFailCause.SERVICE_OPTION_NOT_SUBSCRIBED),
    SERVICE_OPTION_OUT_OF_ORDER(DataFailCause.SERVICE_OPTION_OUT_OF_ORDER),
    NSAPI_IN_USE(DataFailCause.NSAPI_IN_USE),
    PROTOCOL_ERRORS(DataFailCause.PROTOCOL_ERRORS),
    ERROR_UNSPECIFIED(DataFailCause.ERROR_UNSPECIFIED),
    UNKNOWN(DataFailCause.UNKNOWN);

    private final int mErrorCode;

    DcFailCause(int errorCode) {
        mErrorCode = errorCode;
    }

    public int getErrorCode() {
        return mErrorCode;
    }

    public static DcFailCause fromInt(int errorCode) {
        for (DcFailCause fc : values()) {
            if (fc.mErrorCode == errorCode) {
                return fc;
            }
        }
        return UNKNOWN;
    }
}
