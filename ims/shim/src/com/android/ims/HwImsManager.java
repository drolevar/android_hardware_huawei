/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.ims;

import android.content.Context;
import android.os.SystemProperties;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsMmTelManager;

public class HwImsManager {
    private static int resolveSlotId(int slotOrSubId) {
        if (slotOrSubId == 0 || slotOrSubId == 1) {
            return slotOrSubId;
        }
        int slot = SubscriptionManager.getSlotIndex(slotOrSubId);
        return (slot == 0 || slot == 1) ? slot : 0;
    }

    public static boolean isSlotActiveAndUiccEnabled(Context context, int slotOrSubId) {
        if (context == null) {
            return false;
        }
        try {
            SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
            if (sm == null) {
                return false;
            }
            if (slotOrSubId > 1 && SubscriptionManager.isValidSubscriptionId(slotOrSubId)) {
                SubscriptionInfo subInfo = sm.getActiveSubscriptionInfo(slotOrSubId);
                return subInfo != null && subInfo.areUiccApplicationsEnabled();
            }
            int slotId = resolveSlotId(slotOrSubId);
            SubscriptionInfo slotInfo = sm.getActiveSubscriptionInfoForSimSlotIndex(slotId);
            return slotInfo != null
                    && slotInfo.areUiccApplicationsEnabled()
                    && SubscriptionManager.isValidSubscriptionId(slotInfo.getSubscriptionId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ImsManager getAospImsManager(Context context, int slotOrSubId) {
        try {
            return ImsManager.getInstance(context, resolveSlotId(slotOrSubId));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ImsMmTelManager getMmTelManager(Context context, int slotOrSubId) {
        try {
            SubscriptionManager sm =
                    context != null ? context.getSystemService(SubscriptionManager.class) : null;
            int slotId = resolveSlotId(slotOrSubId);
            int[] subIds = sm != null ? sm.getSubscriptionIds(slotId) : null;
            if (subIds != null
                    && subIds.length > 0
                    && SubscriptionManager.isValidSubscriptionId(subIds[0])) {
                return ImsMmTelManager.createForSubscriptionId(subIds[0]);
            }
            if (slotOrSubId > 1 && SubscriptionManager.isValidSubscriptionId(slotOrSubId)) {
                return ImsMmTelManager.createForSubscriptionId(slotOrSubId);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isDualImsAvailable() {
        return true;
    }

    public static boolean isEnhanced4gLteModeSettingEnabledByUser(Context context, int slotId) {
        if (!isSlotActiveAndUiccEnabled(context, slotId)) {
            return false;
        }
        try {
            ImsMmTelManager mgr = getMmTelManager(context, slotId);
            if (mgr != null) {
                return mgr.isAdvancedCallingSettingEnabled();
            }
            ImsManager ims = getAospImsManager(context, slotId);
            if (ims != null) {
                return ims.isEnhanced4gLteModeSettingEnabledByUser();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static boolean isNonTtyOrTtyOnVolteEnabled(Context context, int slotId) {
        try {
            ImsManager ims = getAospImsManager(context, slotId);
            if (ims != null) {
                return ims.isNonTtyOrTtyOnVolteEnabled();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static boolean isVtEnabledByPlatform(Context context, int slotId) {
        try {
            ImsManager ims = getAospImsManager(context, slotId);
            if (ims != null) {
                return ims.isVtEnabledByPlatform();
            }
        } catch (Throwable ignored) {
        }
        return SystemProperties.getBoolean("ro.config.hw_vtlte_on", false);
    }

    public static boolean isVtEnabledByUser(Context context, int slotId) {
        try {
            ImsMmTelManager mgr = getMmTelManager(context, slotId);
            if (mgr != null) {
                return mgr.isVtSettingEnabled();
            }
            ImsManager ims = getAospImsManager(context, slotId);
            if (ims != null) {
                return ims.isVtEnabledByUser();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isWfcEnabledByPlatform(Context context, int slotId) {
        if (!SystemProperties.getBoolean("ro.config.hw_vowifi", false)) {
            return false;
        }
        try {
            ImsManager ims = getAospImsManager(context, slotId);
            if (ims != null) {
                return ims.isWfcEnabledByPlatform();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static boolean isWfcEnabledByUser(Context context, int slotId) {
        if (!SystemProperties.getBoolean("ro.config.hw_vowifi", false)) {
            return false;
        }
        try {
            ImsMmTelManager mgr = getMmTelManager(context, slotId);
            if (mgr != null) {
                return mgr.isVoWiFiSettingEnabled();
            }
            ImsManager ims = getAospImsManager(context, slotId);
            if (ims != null) {
                return ims.isWfcEnabledByUser();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isWfcRoamingEnabledByUser(Context context, int slotId) {
        if (!SystemProperties.getBoolean("ro.config.hw_vowifi", false)) {
            return false;
        }
        try {
            ImsMmTelManager mgr = getMmTelManager(context, slotId);
            if (mgr != null) {
                return mgr.isVoWiFiRoamingSettingEnabled();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static int getWfcMode(Context context, boolean roaming, int slotId) {
        try {
            ImsMmTelManager mgr = getMmTelManager(context, slotId);
            if (mgr != null) {
                return roaming ? mgr.getVoWiFiRoamingModeSetting() : mgr.getVoWiFiModeSetting();
            }
            ImsManager ims = getAospImsManager(context, slotId);
            if (ims != null) {
                return ims.getWfcMode(roaming);
            }
        } catch (Throwable ignored) {
        }
        return ImsMmTelManager.WIFI_MODE_CELLULAR_PREFERRED;
    }
}
