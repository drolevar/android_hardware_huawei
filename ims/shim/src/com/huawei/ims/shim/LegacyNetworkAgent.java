/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.ims.shim;

import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.net.NetworkScore;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.os.Looper;
import android.telephony.SubscriptionManager;
import android.util.Log;

public class LegacyNetworkAgent extends NetworkAgent {
    private final String mTag;
    private volatile boolean mAgentRegistered;
    private volatile boolean mAgentUnregistered;

    public LegacyNetworkAgent(
            Looper looper,
            Context context,
            String logTag,
            NetworkInfo ni,
            NetworkCapabilities nc,
            LinkProperties lp,
            int score,
            NetworkMisc misc) {
        super(
                context,
                looper != null ? looper : Looper.getMainLooper(),
                logTag != null ? logTag : "VowifiNetworkAgent",
                translateCapabilities(nc),
                lp != null ? lp : new LinkProperties(),
                new NetworkScore.Builder().setLegacyInt(score).build(),
                buildConfig(ni, misc),
                LegacyNetworkFactory.getLatestProvider());
        mTag = logTag != null ? logTag : "VowifiNetworkAgent";
        if (ni != null && ni.getDetailedState() == NetworkInfo.DetailedState.CONNECTED) {
            ensureRegisteredAndConnected();
        }
    }

    private static NetworkAgentConfig buildConfig(NetworkInfo ni, NetworkMisc misc) {
        NetworkAgentConfig.Builder builder = new NetworkAgentConfig.Builder();
        if (ni != null) {
            builder.setLegacyType(ni.getType());
            builder.setLegacyTypeName(ni.getTypeName() != null ? ni.getTypeName() : "WIFI");
            if (ni.getExtraInfo() != null) {
                builder.setLegacyExtraInfo(ni.getExtraInfo());
            }
        }
        if (misc != null && misc.explicitlySelected) {
            builder.setExplicitlySelected(true);
            builder.setUnvalidatedConnectivityAcceptable(misc.acceptUnvalidated);
        }
        return builder.build();
    }

    public static NetworkCapabilities translateCapabilities(NetworkCapabilities src) {
        NetworkCapabilities base = src != null ? src : new NetworkCapabilities();
        NetworkCapabilities.Builder builder =
                new NetworkCapabilities.Builder(base)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        NetworkSpecifier spec = base.getNetworkSpecifier();
        if (spec != null && !(spec instanceof TelephonyNetworkSpecifier)) {
            int subId = MapconShim.parseSubIdFromSpecifier(spec);
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                try {
                    builder.setNetworkSpecifier(
                            new TelephonyNetworkSpecifier.Builder()
                                    .setSubscriptionId(subId)
                                    .build());
                } catch (Throwable t) {
                    Log.w("LegacyNetworkAgent", "Unable to convert NetworkSpecifier", t);
                }
            }
        }
        return builder.build();
    }

    private synchronized void ensureRegisteredAndConnected() {
        if (mAgentUnregistered) {
            return;
        }
        if (!mAgentRegistered) {
            try {
                register();
                markConnected();
                mAgentRegistered = true;
            } catch (Throwable t) {
                Log.w(mTag, "Failed to register/connect LegacyNetworkAgent", t);
            }
        }
    }

    public synchronized void sendNetworkInfo(NetworkInfo networkInfo) {
        if (networkInfo == null) {
            return;
        }
        NetworkInfo.DetailedState state = networkInfo.getDetailedState();
        if (state == NetworkInfo.DetailedState.CONNECTED) {
            ensureRegisteredAndConnected();
        } else if (state == NetworkInfo.DetailedState.DISCONNECTED && !mAgentUnregistered) {
            mAgentUnregistered = true;
            if (mAgentRegistered) {
                try {
                    unregister();
                } catch (Throwable t) {
                    Log.w(mTag, "Failed to unregister LegacyNetworkAgent", t);
                }
            }
        }
    }

    @Override
    public void sendNetworkCapabilities(NetworkCapabilities networkCapabilities) {
        super.sendNetworkCapabilities(translateCapabilities(networkCapabilities));
    }

    @Override
    public void onNetworkUnwanted() {
        unwanted();
    }

    protected void unwanted() {}
}
