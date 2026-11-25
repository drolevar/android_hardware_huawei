//
// SPDX-FileCopyrightText: 2026 The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package androidx.camera.extensions.impl;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Pair;
import android.util.Range;
import android.util.Size;

import java.util.Collections;
import java.util.List;

abstract class BaseHuaweiImageCaptureExtender implements ImageCaptureExtenderImpl {
    private static final int STAGE_ID = 0;

    private final HuaweiVendorTags.Mode mMode;
    private String mCameraId = "0";
    private CameraCharacteristics mCharacteristics;

    BaseHuaweiImageCaptureExtender(HuaweiVendorTags.Mode mode) {
        mMode = mode;
    }

    @Override
    public boolean isExtensionAvailable(
            String cameraId, CameraCharacteristics cameraCharacteristics) {
        return HuaweiVendorTags.isSupportedCameraId(cameraId);
    }

    @Override
    public void init(String cameraId, CameraCharacteristics cameraCharacteristics) {
        if (cameraId != null) {
            mCameraId = cameraId;
        }
        mCharacteristics = cameraCharacteristics;
    }

    @Override
    public void onInit(
            String cameraId, CameraCharacteristics cameraCharacteristics, Context context) {
        if (cameraId != null) {
            mCameraId = cameraId;
        }
        mCharacteristics = cameraCharacteristics;
    }

    @Override
    public void onDeInit() {
        mCharacteristics = null;
    }

    @Override
    public CaptureStageImpl onPresetSession() {
        return new SimpleCaptureStage(
                STAGE_ID, HuaweiVendorTags.buildPresetParameters(mCameraId, mMode));
    }

    @Override
    public CaptureStageImpl onEnableSession() {
        return new SimpleCaptureStage(
                STAGE_ID, HuaweiVendorTags.buildPresetParameters(mCameraId, mMode));
    }

    @Override
    public CaptureStageImpl onDisableSession() {
        return new SimpleCaptureStage(STAGE_ID, Collections.emptyList());
    }

    @Override
    public CaptureProcessorImpl getCaptureProcessor() {
        return null;
    }

    @Override
    public List<CaptureStageImpl> getCaptureStages() {
        return Collections.singletonList(
                new SimpleCaptureStage(
                        STAGE_ID, HuaweiVendorTags.buildPresetParameters(mCameraId, mMode)));
    }

    @Override
    public int getMaxCaptureStage() {
        return 1;
    }

    @Override
    public List<Pair<Integer, Size[]>> getSupportedResolutions() {
        return null;
    }

    @Override
    public List<Pair<Integer, Size[]>> getSupportedPostviewResolutions(Size captureSize) {
        return null;
    }

    @Override
    public Range<Long> getEstimatedCaptureLatencyRange(Size captureOutputSize) {
        return null;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<CaptureRequest.Key> getAvailableCaptureRequestKeys() {
        return HuaweiVendorTags.buildAvailableRequestKeys(mCharacteristics);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<CaptureResult.Key> getAvailableCaptureResultKeys() {
        return HuaweiVendorTags.buildAvailableResultKeys(mCharacteristics);
    }

    @Override
    public boolean isCaptureProcessProgressAvailable() {
        return false;
    }

    @Override
    public Pair<Long, Long> getRealtimeCaptureLatency() {
        return null;
    }

    @Override
    public boolean isPostviewAvailable() {
        return false;
    }

    @Override
    public int onSessionType() {
        return -1;
    }
}
