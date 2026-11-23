/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.location;

import java.util.List;

/** Compile-time stub matching AOSP android.location.LocationManager (not packaged into DEX). */
public class LocationManager {
    public List<String> getAllProviders() {
        return null;
    }

    public void requestLocationUpdates(
            String provider, long minTimeMs, float minDistanceM, LocationListener listener) {}

    public Location getLastKnownLocation(String provider) {
        return null;
    }
}
