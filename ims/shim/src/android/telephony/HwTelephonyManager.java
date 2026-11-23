/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.telephony;

import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

public class HwTelephonyManager {
    public static final int SINGLE_MODE_USIM_CARD = 20;
    public static final int UNKNOWN_CARD = -1;

    private static final HwTelephonyManager INSTANCE = new HwTelephonyManager();

    private static TelephonyManager getTelephonyManager() {
        try {
            Phone defPhone = PhoneFactory.getDefaultPhone();
            if (defPhone != null && defPhone.getContext() != null) {
                return defPhone.getContext().getSystemService(TelephonyManager.class);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static HwTelephonyManager getDefault() {
        return INSTANCE;
    }

    public static boolean isMultiSimEnabled() {
        try {
            TelephonyManager tm = getTelephonyManager();
            if (tm != null) {
                return tm.getActiveModemCount() > 1;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public boolean isNrSupported() {
        // Kirin 970 Balong 765 is LTE-Advanced Pro (no 5G NR).
        return false;
    }

    public boolean isCTSimCard(int slotId) {
        try {
            Phone defPhone = PhoneFactory.getDefaultPhone();
            if (defPhone == null || defPhone.getContext() == null) {
                return false;
            }
            TelephonyManager tm = defPhone.getContext().getSystemService(TelephonyManager.class);
            SubscriptionManager sm =
                    defPhone.getContext().getSystemService(SubscriptionManager.class);
            if (tm == null) {
                return false;
            }
            int[] subIds = sm != null ? sm.getSubscriptionIds(slotId) : null;
            String op = (subIds != null && subIds.length > 0)
                    ? tm.createForSubscriptionId(subIds[0]).getSimOperator()
                    : tm.getSimOperator();
            return "46003".equals(op) || "46005".equals(op) || "46011".equals(op);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public int getDefault4GSlotId() {
        try {
            int subId = SubscriptionManager.getDefaultDataSubscriptionId();
            int slotId = SubscriptionManager.getSlotIndex(subId);
            if (slotId >= 0) {
                return slotId;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    public int getCardType(int slotId) {
        try {
            TelephonyManager tm = getTelephonyManager();
            if (tm != null) {
                int simState = tm.getSimState(slotId);
                if (simState == TelephonyManager.SIM_STATE_ABSENT
                        || simState == TelephonyManager.SIM_STATE_UNKNOWN) {
                    return UNKNOWN_CARD;
                }
            }
        } catch (Throwable ignored) {
        }
        return SINGLE_MODE_USIM_CARD;
    }

    public int getDataRegisteredState(int subOrSlotId) {
        try {
            int slotId = (subOrSlotId == 0 || subOrSlotId == 1)
                    ? subOrSlotId
                    : SubscriptionManager.getSlotIndex(subOrSlotId);
            if (slotId < 0) {
                slotId = 0;
            }
            Phone phone = PhoneFactory.getPhone(slotId);
            ServiceState ss = phone != null ? phone.getServiceState() : null;
            if (ss != null) {
                NetworkRegistrationInfo nri =
                        ss.getNetworkRegistrationInfo(
                                NetworkRegistrationInfo.DOMAIN_PS,
                                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
                if (nri != null) {
                    return nri.isRegistered()
                            ? ServiceState.STATE_IN_SERVICE
                            : ServiceState.STATE_OUT_OF_SERVICE;
                }
            }
        } catch (Throwable ignored) {
        }
        return ServiceState.STATE_IN_SERVICE;
    }

    public int getImsDomain(int slotId) {
        try {
            Phone phone = PhoneFactory.getPhone(slotId);
            if (phone != null && phone.getImsPhone() != null) {
                int tech = phone.getImsPhone().getImsRegistrationTech();
                if (tech == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN) {
                    return 1; // Wi-Fi / IWLAN domain
                }
            }
        } catch (Throwable ignored) {
        }
        return 0; // LTE domain
    }
}
