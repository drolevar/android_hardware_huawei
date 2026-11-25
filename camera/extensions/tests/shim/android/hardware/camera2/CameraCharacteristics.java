package android.hardware.camera2;

import java.util.Collections;
import java.util.List;

public final class CameraCharacteristics {
    private final List<CaptureRequest.Key<?>> mRequestKeys;
    private final List<CaptureResult.Key<?>> mResultKeys;

    public CameraCharacteristics() {
        this(Collections.emptyList(), Collections.emptyList());
    }

    public CameraCharacteristics(
            List<CaptureRequest.Key<?>> requestKeys,
            List<CaptureResult.Key<?>> resultKeys) {
        mRequestKeys = requestKeys;
        mResultKeys = resultKeys;
    }

    public List<CaptureRequest.Key<?>> getAvailableCaptureRequestKeys() {
        return mRequestKeys;
    }

    public List<CaptureResult.Key<?>> getAvailableCaptureResultKeys() {
        return mResultKeys;
    }
}
