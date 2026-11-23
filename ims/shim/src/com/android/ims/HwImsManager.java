/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.ims;

import android.content.Context;
import android.os.SystemProperties;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsMmTelManager;

public class HwImsManager {
    private static ImsManager getAospImsManager(Context context, int slotId) {
        try {
            return ImsManager.getInstance(context, slotId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ImsMmTelManager getMmTelManager(Context context, int slotId) {
        try {
            SubscriptionManager sm =
                    context != null ? context.getSystemService(SubscriptionManager.class) : null;
            int[] subIds = sm != null ? sm.getSubscriptionIds(slotId) : null;
            if (subIds != null && subIds.length > 0 && SubscriptionManager.isValidSubscriptionId(subIds[0])) {
                return ImsMmTelManager.createForSubscriptionId(subIds[0]);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isDualImsAvailable() {
        return true;
    }

    public static boolean isEnhanced4gLteModeSettingEnabledByUser(Context context, int slotId) {
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
        if (!SystemProperties.getBoolean("ro.vendor.config.hw_vowifi", false)) {
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
        if (!SystemProperties.getBoolean("ro.vendor.config.hw_vowifi", false)) {
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
