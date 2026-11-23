/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.telephony;

import android.content.Context;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import com.android.internal.telephony.IPhoneCallback;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.huawei.ims.HwImsShim;
import com.huawei.ims.shim.HisiRadioBridge;

public class HwTelephonyManager {
    public static final int SINGLE_MODE_USIM_CARD = 20;
    public static final int UNKNOWN_CARD = -1;
    public static final int APPTYPE_USIM = 2;
    public static final int APPTYPE_UNKNOWN = 0;

    public static final int PHONE_EVENT_RADIO_AVAILABLE =
            HisiRadioBridge.PHONE_EVENT_RADIO_AVAILABLE;
    public static final int PHONE_EVENT_RADIO_UNAVAILABLE =
            HisiRadioBridge.PHONE_EVENT_RADIO_UNAVAILABLE;
    public static final int PHONE_EVENT_IMSA_TO_MAPCON =
            HisiRadioBridge.PHONE_EVENT_IMSA_TO_MAPCON;

    private static final HwTelephonyManager INSTANCE = new HwTelephonyManager();

    public HwTelephonyManager() {}

    private static Context getPhoneContext() {
        try {
            Phone defPhone = PhoneFactory.getDefaultPhone();
            if (defPhone != null) {
                return defPhone.getContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static TelephonyManager getTelephonyManager() {
        Context ctx = getPhoneContext();
        return ctx != null ? ctx.getSystemService(TelephonyManager.class) : null;
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

    public boolean isVSimEnabled() {
        return false;
    }

    public boolean isCTSimCard(int slotId) {
        try {
            Context ctx = getPhoneContext();
            if (ctx == null) {
                return false;
            }
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
            if (tm == null) {
                return false;
            }
            int[] subIds = sm != null ? sm.getSubscriptionIds(slotId) : null;
            String op =
                    (subIds != null && subIds.length > 0)
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

    public int getUiccAppType(int slotId) {
        return getCardType(slotId) == UNKNOWN_CARD ? APPTYPE_UNKNOWN : APPTYPE_USIM;
    }

    public CellLocation getCellLocation(int slotId) {
        try {
            Context ctx = getPhoneContext();
            if (ctx == null) {
                return null;
            }
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
            if (tm == null) {
                return null;
            }
            int[] subIds = sm != null ? sm.getSubscriptionIds(slotId) : null;
            if (subIds != null && subIds.length > 0) {
                return tm.createForSubscriptionId(subIds[0]).getCellLocation();
            }
            return tm.getCellLocation();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public int getSubState(long subId) {
        try {
            int id = (int) subId;
            Context ctx = getPhoneContext();
            SubscriptionManager sm =
                    ctx != null ? ctx.getSystemService(SubscriptionManager.class) : null;
            if (sm != null) {
                SubscriptionInfo info = sm.getActiveSubscriptionInfo(id);
                if (info != null) {
                    return info.areUiccApplicationsEnabled() ? 1 : 0;
                }
                if (id == 0 || id == 1) {
                    SubscriptionInfo slotInfo = sm.getActiveSubscriptionInfoForSimSlotIndex(id);
                    if (slotInfo != null) {
                        return slotInfo.areUiccApplicationsEnabled() ? 1 : 0;
                    }
                }
            }
            if (SubscriptionManager.isValidSubscriptionId(id)) {
                return 1;
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    public int getDataRegisteredState(int subOrSlotId) {
        try {
            int slotId =
                    (subOrSlotId == 0 || subOrSlotId == 1)
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

    public boolean isImsRegistered(int slotId) {
        try {
            Phone phone = PhoneFactory.getPhone(slotId);
            if (phone != null) {
                if (phone.isImsRegistered()) {
                    return true;
                }
                if (phone.getImsPhone() != null) {
                    return phone.getImsPhone().isImsRegistered();
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public boolean getImsSwitch(int slotId) {
        try {
            Phone phone = PhoneFactory.getPhone(slotId);
            return HwImsShim.getImsSwitch(phone);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public void setImsSwitch(int slotId, boolean on) {
        if (on) {
            HwImsShim.enableModemImsSwitch(slotId);
        } else {
            HwImsShim.disableModemImsSwitch(slotId);
        }
    }

    public boolean isRadioAvailable(int slotId) {
        try {
            Phone phone = PhoneFactory.getPhone(slotId);
            if (phone != null) {
                return phone.isRadioAvailable();
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        return HisiRadioBridge.getInstance().getImsDomain(slotId);
    }

    public boolean setImsDomainConfig(int slotId, int domainType) {
        return HisiRadioBridge.getInstance().setImsDomain(slotId, domainType);
    }

    public boolean handleMapconImsaReq(int slotId, byte[] msg) {
        return HisiRadioBridge.getInstance().vowifiToImsaMsg(slotId, msg);
    }

    public UiccAuthResponse handleUiccAuth(int slotId, int authType, byte[] rand, byte[] auth) {
        return HisiRadioBridge.getInstance().uiccAuth(slotId, authType, rand, auth);
    }

    public boolean registerForPhoneEvent(int slotId, IPhoneCallback callback, int events) {
        return HisiRadioBridge.getInstance().registerForPhoneEvent(slotId, callback, events);
    }

    public void unregisterForPhoneEvent(IPhoneCallback callback) {
        HisiRadioBridge.getInstance().unregisterForPhoneEvent(callback);
    }
}
