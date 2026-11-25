//
// SPDX-FileCopyrightText: 2026 The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package androidx.camera.extensions.impl;

import android.hardware.camera2.CaptureRequest;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SimpleCaptureStage implements CaptureStageImpl {
    private final int mId;
    @SuppressWarnings("rawtypes")
    private final List<Pair<CaptureRequest.Key, Object>> mParameters;

    @SuppressWarnings("rawtypes")
    SimpleCaptureStage(int id, List<Pair<CaptureRequest.Key, Object>> parameters) {
        mId = id;
        mParameters = Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    @Override
    public int getId() {
        return mId;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<Pair<CaptureRequest.Key, Object>> getParameters() {
        return mParameters;
    }
}
