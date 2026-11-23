/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.ims.shim;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.MatchAllNetworkSpecifier;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;
import android.util.Log;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class LegacyNetworkFactory extends Handler {
    private static final Map<IBinder, LegacyNetworkFactory> REGISTRY =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile NetworkProvider sLatestProvider;

    private final Context mContext;
    private final String mLogTag;
    private final NetworkCapabilities mBaseFilter;
    private final Set<NetworkRequest> mActiveRequests = new HashSet<>();
    private final Messenger mSelfMessenger;
    private NetworkProvider mProvider;
    private int mScoreFilter = 100;
    private boolean mRegistered;

    private final NetworkProvider.NetworkOfferCallback mWifiOfferCallback =
            new NetworkProvider.NetworkOfferCallback() {
                @Override
                public void onNetworkNeeded(NetworkRequest request) {
                    handleOfferNeeded(request);
                }

                @Override
                public void onNetworkUnneeded(NetworkRequest request) {
                    handleOfferUnneeded(request);
                }
            };

    private final NetworkProvider.NetworkOfferCallback mCellOfferCallback =
            new NetworkProvider.NetworkOfferCallback() {
                @Override
                public void onNetworkNeeded(NetworkRequest request) {
                    handleOfferNeeded(request);
                }

                @Override
                public void onNetworkUnneeded(NetworkRequest request) {
                    handleOfferUnneeded(request);
                }
            };

    public LegacyNetworkFactory(
            Looper looper, Context context, String logTag, NetworkCapabilities filter) {
        super(looper != null ? looper : Looper.getMainLooper());
        mContext = context;
        mLogTag = logTag != null ? logTag : "LegacyNetworkFactory";
        mBaseFilter = filter != null ? new NetworkCapabilities(filter) : new NetworkCapabilities();
        mSelfMessenger = new Messenger(this);
        REGISTRY.put(mSelfMessenger.getBinder(), this);
    }

    static LegacyNetworkFactory findByMessenger(Messenger messenger) {
        if (messenger == null) {
            return null;
        }
        return REGISTRY.get(messenger.getBinder());
    }

    public static NetworkProvider getLatestProvider() {
        return sLatestProvider;
    }

    public void setScoreFilter(int score) {
        mScoreFilter = score;
        post(
                () -> {
                    if (mRegistered && mProvider != null) {
                        registerOffersLocked();
                    }
                });
    }

    public int registerProvider(ConnectivityManager cm, String name) {
        if (mContext == null || cm == null) {
            return NetworkProvider.ID_NONE;
        }
        if (mProvider == null) {
            mProvider =
                    new NetworkProvider(
                            mContext, getLooper(), name != null ? name : mLogTag);
        }
        if (!mRegistered) {
            try {
                cm.registerNetworkProvider(mProvider);
                mRegistered = true;
                sLatestProvider = mProvider;
                registerOffersLocked();
                Log.i(mLogTag, "Registered VoWiFi NetworkProvider id=" + mProvider.getProviderId());
            } catch (Throwable t) {
                Log.w(mLogTag, "Failed to register VoWiFi NetworkProvider", t);
                return NetworkProvider.ID_NONE;
            }
        }
        return mProvider.getProviderId();
    }

    public void unregisterProvider(ConnectivityManager cm) {
        if (!mRegistered || mProvider == null || cm == null) {
            return;
        }
        try {
            mProvider.unregisterNetworkOffer(mWifiOfferCallback);
        } catch (Throwable ignored) {
        }
        try {
            mProvider.unregisterNetworkOffer(mCellOfferCallback);
        } catch (Throwable ignored) {
        }
        try {
            cm.unregisterNetworkProvider(mProvider);
        } catch (Throwable t) {
            Log.w(mLogTag, "Failed to unregister VoWiFi NetworkProvider", t);
        }
        mRegistered = false;
        mActiveRequests.clear();
    }

    private void registerOffersLocked() {
        if (mProvider == null) {
            return;
        }
        NetworkScore score = new NetworkScore.Builder().setLegacyInt(mScoreFilter).build();
        try {
            mProvider.registerNetworkOffer(
                    score,
                    buildSingleTransportOffer(NetworkCapabilities.TRANSPORT_WIFI),
                    this::post,
                    mWifiOfferCallback);
            mProvider.registerNetworkOffer(
                    score,
                    buildSingleTransportOffer(NetworkCapabilities.TRANSPORT_CELLULAR),
                    this::post,
                    mCellOfferCallback);
        } catch (Throwable t) {
            Log.w(mLogTag, "Failed to register VoWiFi NetworkOffer", t);
        }
    }

    private NetworkCapabilities buildSingleTransportOffer(int transportType) {
        return new NetworkCapabilities.Builder(mBaseFilter)
                .setNetworkSpecifier(null)
                .removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(transportType)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_MMS)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .setNetworkSpecifier(new MatchAllNetworkSpecifier())
                .build();
    }

    private void handleOfferNeeded(final NetworkRequest request) {
        post(
                () -> {
                    if (request == null) {
                        return;
                    }
                    if (acceptRequest(request, 0) && mActiveRequests.add(request)) {
                        needNetworkFor(request, 0);
                    }
                });
    }

    private void handleOfferUnneeded(final NetworkRequest request) {
        post(
                () -> {
                    if (request != null && mActiveRequests.remove(request)) {
                        releaseNetworkFor(request);
                    }
                });
    }

    public boolean acceptRequest(NetworkRequest request, int score) {
        return true;
    }

    protected void needNetworkFor(NetworkRequest networkRequest, int score) {}

    protected void releaseNetworkFor(NetworkRequest networkRequest) {}
}
