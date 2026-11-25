//
// SPDX-FileCopyrightText: 2026 The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package androidx.camera.extensions.impl;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Pair;
import android.util.Size;

import java.util.Collections;
import java.util.List;

abstract class BaseHuaweiPreviewExtender implements PreviewExtenderImpl {
    private static final int STAGE_ID = 0;

    private final HuaweiVendorTags.Mode mMode;
    private String mCameraId = "0";

    BaseHuaweiPreviewExtender(HuaweiVendorTags.Mode mode) {
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
    }

    @Override
    public void onInit(
            String cameraId, CameraCharacteristics cameraCharacteristics, Context context) {
        if (cameraId != null) {
            mCameraId = cameraId;
        }
    }

    @Override
    public void onDeInit() {}

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
    public CaptureStageImpl getCaptureStage() {
        return new SimpleCaptureStage(
                STAGE_ID, HuaweiVendorTags.buildPresetParameters(mCameraId, mMode));
    }

    @Override
    public ProcessorType getProcessorType() {
        return ProcessorType.PROCESSOR_TYPE_NONE;
    }

    @Override
    public ProcessorImpl getProcessor() {
        return null;
    }

    @Override
    public List<Pair<Integer, Size[]>> getSupportedResolutions() {
        return null;
    }

    @Override
    public int onSessionType() {
        return -1;
    }
}
