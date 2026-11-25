//
// SPDX-FileCopyrightText: 2026 The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package androidx.camera.extensions.impl;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class HuaweiVendorTags {
    private HuaweiVendorTags() {}

    enum Mode {
        AUTO,
        HDR,
        BOKEH,
        NIGHT,
        FACE_RETOUCH
    }

    // Verified EMUI 12 .225 Huawei vendor keys and exact native wire types:
    // TYPE_INT32 (1) -> Integer.class
    static final CaptureRequest.Key<Integer> KEY_CAMERA_SCENE_MODE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.cameraSceneMode",
                    Integer.class);
    static final CaptureRequest.Key<Integer> KEY_CAMERA_SESSION_SCENE_MODE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.cameraSessionSceneMode",
                    Integer.class);
    static final CaptureRequest.Key<Integer> KEY_APERTURE_VALUE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.apertureValue",
                    Integer.class);
    static final CaptureRequest.Key<Integer> KEY_FACE_BEAUTY_LEVEL =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.faceBeautyLevel",
                    Integer.class);

    // TYPE_BYTE (0) -> Byte.class
    static final CaptureRequest.Key<Byte> KEY_DUAL_SENSOR_MODE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.dualSensorMode",
                    Byte.class);
    static final CaptureRequest.Key<Byte> KEY_SENSOR_HDR_MODE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.sensorHdrMode",
                    Byte.class);
    static final CaptureRequest.Key<Byte> KEY_APERTURE_MODE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.apertureMode",
                    Byte.class);
    static final CaptureRequest.Key<Byte> KEY_OPTICAL_ZOOM_MODE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.opticalZoomMode",
                    Byte.class);
    static final CaptureRequest.Key<Byte> KEY_FACE_BEAUTY_MODE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.faceBeautyMode",
                    Byte.class);
    static final CaptureRequest.Key<Byte> KEY_NORMAL_FRONT_NIGHT_MODE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.normalFrontNightMode",
                    Byte.class);
    static final CaptureRequest.Key<Byte> KEY_PORTRAIT_MODE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.portraitMode",
                    Byte.class);
    static final CaptureRequest.Key<Byte> KEY_FRONT_VIRTUAL_EFFECT_MODE =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.frontVirtualEffectMode",
                    Byte.class);
    static final CaptureRequest.Key<Byte> KEY_FRONT_VIRTUAL_EFFECT_LEVEL =
            new CaptureRequest.Key<>("com.huawei.capture.metadata.frontVirtualEffectLevel",
                    Byte.class);

    static boolean isSupportedCameraId(String cameraId) {
        return "0".equals(cameraId) || "1".equals(cameraId);
    }

    @SuppressWarnings("rawtypes")
    private static Pair<CaptureRequest.Key, Object> param(
            CaptureRequest.Key<?> key, Object value) {
        return Pair.create((CaptureRequest.Key) key, value);
    }

    @SuppressWarnings("rawtypes")
    static List<Pair<CaptureRequest.Key, Object>> buildPresetParameters(
            String cameraId, Mode mode) {
        final boolean rear = "0".equals(cameraId);
        List<Pair<CaptureRequest.Key, Object>> params = new ArrayList<>();

        switch (mode) {
            case AUTO:
                params.add(param(KEY_CAMERA_SESSION_SCENE_MODE, Integer.valueOf(0)));
                params.add(param(KEY_CAMERA_SCENE_MODE, Integer.valueOf(0)));
                if (rear) {
                    params.add(param(KEY_DUAL_SENSOR_MODE, Byte.valueOf((byte) 3)));
                }
                break;

            case HDR:
                params.add(param(KEY_CAMERA_SESSION_SCENE_MODE, Integer.valueOf(5)));
                params.add(param(KEY_CAMERA_SCENE_MODE, Integer.valueOf(5)));
                if (rear) {
                    params.add(param(KEY_DUAL_SENSOR_MODE, Byte.valueOf((byte) 3)));
                }
                params.add(param(KEY_SENSOR_HDR_MODE, Byte.valueOf((byte) 1)));
                break;

            case BOKEH:
                params.add(param(KEY_CAMERA_SESSION_SCENE_MODE, Integer.valueOf(23)));
                params.add(param(KEY_CAMERA_SCENE_MODE, Integer.valueOf(23)));
                params.add(param(KEY_PORTRAIT_MODE, Byte.valueOf((byte) 1)));
                if (rear) {
                    params.add(param(KEY_DUAL_SENSOR_MODE, Byte.valueOf((byte) 3)));
                    params.add(param(KEY_APERTURE_MODE, Byte.valueOf((byte) 1)));
                    params.add(param(KEY_APERTURE_VALUE, Integer.valueOf(4)));
                } else {
                    params.add(param(KEY_FRONT_VIRTUAL_EFFECT_MODE, Byte.valueOf((byte) 1)));
                    params.add(param(KEY_FRONT_VIRTUAL_EFFECT_LEVEL, Byte.valueOf((byte) 5)));
                    params.add(param(KEY_FACE_BEAUTY_MODE, Byte.valueOf((byte) 1)));
                    params.add(param(KEY_FACE_BEAUTY_LEVEL, Integer.valueOf(3)));
                }
                break;

            case NIGHT:
                params.add(param(KEY_CAMERA_SESSION_SCENE_MODE, Integer.valueOf(7)));
                params.add(param(KEY_CAMERA_SCENE_MODE, Integer.valueOf(7)));
                if (rear) {
                    params.add(param(KEY_DUAL_SENSOR_MODE, Byte.valueOf((byte) 3)));
                } else {
                    params.add(param(KEY_NORMAL_FRONT_NIGHT_MODE, Byte.valueOf((byte) 1)));
                }
                break;

            case FACE_RETOUCH:
                params.add(param(KEY_CAMERA_SESSION_SCENE_MODE, Integer.valueOf(16)));
                params.add(param(KEY_CAMERA_SCENE_MODE, Integer.valueOf(16)));
                if (rear) {
                    params.add(param(KEY_DUAL_SENSOR_MODE, Byte.valueOf((byte) 3)));
                }
                params.add(param(KEY_FACE_BEAUTY_MODE, Byte.valueOf((byte) 1)));
                params.add(param(KEY_FACE_BEAUTY_LEVEL, Integer.valueOf(5)));
                break;
        }
        return params;
    }

    @SuppressWarnings("rawtypes")
    private static List<CaptureRequest.Key> candidateRequestKeys() {
        return Arrays.asList(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                CaptureRequest.SCALER_CROP_REGION,
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_REGIONS,
                CaptureRequest.CONTROL_AE_REGIONS,
                CaptureRequest.CONTROL_AWB_REGIONS,
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.FLASH_MODE,
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                CaptureRequest.JPEG_QUALITY,
                CaptureRequest.JPEG_ORIENTATION);
    }

    @SuppressWarnings("rawtypes")
    private static List<CaptureResult.Key> candidateResultKeys() {
        return Arrays.asList(
                CaptureResult.CONTROL_ZOOM_RATIO,
                CaptureResult.SCALER_CROP_REGION,
                CaptureResult.CONTROL_AF_MODE,
                CaptureResult.CONTROL_AF_TRIGGER,
                CaptureResult.CONTROL_AF_STATE,
                CaptureResult.CONTROL_AF_REGIONS,
                CaptureResult.CONTROL_AE_REGIONS,
                CaptureResult.CONTROL_AWB_REGIONS,
                CaptureResult.CONTROL_AE_MODE,
                CaptureResult.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureResult.CONTROL_AE_STATE,
                CaptureResult.FLASH_MODE,
                CaptureResult.FLASH_STATE,
                CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION,
                CaptureResult.JPEG_QUALITY,
                CaptureResult.JPEG_ORIENTATION,
                CaptureResult.SENSOR_TIMESTAMP);
    }

    @SuppressWarnings("rawtypes")
    static List<CaptureRequest.Key> buildAvailableRequestKeys(CameraCharacteristics chars) {
        List<CaptureRequest.Key> candidates = candidateRequestKeys();
        if (chars == null) {
            return Collections.unmodifiableList(candidates);
        }
        List<CaptureRequest.Key<?>> supported = chars.getAvailableCaptureRequestKeys();
        if (supported == null || supported.isEmpty()) {
            return Collections.unmodifiableList(candidates);
        }
        Set<String> supportedNames = new HashSet<>();
        for (CaptureRequest.Key<?> k : supported) {
            supportedNames.add(k.getName());
        }
        supportedNames.add(CaptureRequest.JPEG_QUALITY.getName());
        supportedNames.add(CaptureRequest.JPEG_ORIENTATION.getName());

        List<CaptureRequest.Key> filtered = new ArrayList<>();
        for (CaptureRequest.Key k : candidates) {
            if (supportedNames.contains(k.getName())) {
                filtered.add(k);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    @SuppressWarnings("rawtypes")
    static List<CaptureResult.Key> buildAvailableResultKeys(CameraCharacteristics chars) {
        List<CaptureResult.Key> candidates = candidateResultKeys();
        if (chars == null) {
            return Collections.unmodifiableList(candidates);
        }
        List<CaptureResult.Key<?>> supported = chars.getAvailableCaptureResultKeys();
        if (supported == null || supported.isEmpty()) {
            return Collections.unmodifiableList(candidates);
        }
        Set<String> supportedNames = new HashSet<>();
        for (CaptureResult.Key<?> k : supported) {
            supportedNames.add(k.getName());
        }
        supportedNames.add(CaptureResult.JPEG_QUALITY.getName());
        supportedNames.add(CaptureResult.JPEG_ORIENTATION.getName());
        supportedNames.add(CaptureResult.SENSOR_TIMESTAMP.getName());

        List<CaptureResult.Key> filtered = new ArrayList<>();
        for (CaptureResult.Key k : candidates) {
            if (supportedNames.contains(k.getName())) {
                filtered.add(k);
            }
        }
        return Collections.unmodifiableList(filtered);
    }
}
