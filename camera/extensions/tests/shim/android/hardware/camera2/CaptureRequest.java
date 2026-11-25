package android.hardware.camera2;

import java.util.Objects;

public final class CaptureRequest {
    private CaptureRequest() {}

    public static final class Key<T> {
        private final String mName;
        private final Class<T> mType;

        public Key(String name, Class<T> type) {
            mName = Objects.requireNonNull(name);
            mType = Objects.requireNonNull(type);
        }

        public String getName() {
            return mName;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key<?> other = (Key<?>) o;
            return mName.equals(other.mName) && mType.equals(other.mType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mName, mType);
        }

        @Override
        public String toString() {
            return "CaptureRequest.Key(" + mName + ")";
        }
    }

    public static final Key<Float> CONTROL_ZOOM_RATIO =
            new Key<>("android.control.zoomRatio", Float.class);
    public static final Key<Object> SCALER_CROP_REGION =
            new Key<>("android.scaler.cropRegion", Object.class);
    public static final Key<Integer> CONTROL_AF_MODE =
            new Key<>("android.control.afMode", Integer.class);
    public static final Key<Integer> CONTROL_AF_TRIGGER =
            new Key<>("android.control.afTrigger", Integer.class);
    public static final Key<Object> CONTROL_AF_REGIONS =
            new Key<>("android.control.afRegions", Object.class);
    public static final Key<Object> CONTROL_AE_REGIONS =
            new Key<>("android.control.aeRegions", Object.class);
    public static final Key<Object> CONTROL_AWB_REGIONS =
            new Key<>("android.control.awbRegions", Object.class);
    public static final Key<Integer> CONTROL_AE_MODE =
            new Key<>("android.control.aeMode", Integer.class);
    public static final Key<Integer> CONTROL_AE_PRECAPTURE_TRIGGER =
            new Key<>("android.control.aePrecaptureTrigger", Integer.class);
    public static final Key<Integer> FLASH_MODE =
            new Key<>("android.flash.mode", Integer.class);
    public static final Key<Integer> CONTROL_AE_EXPOSURE_COMPENSATION =
            new Key<>("android.control.aeExposureCompensation", Integer.class);
    public static final Key<Byte> JPEG_QUALITY =
            new Key<>("android.jpeg.quality", Byte.class);
    public static final Key<Integer> JPEG_ORIENTATION =
            new Key<>("android.jpeg.orientation", Integer.class);
}
