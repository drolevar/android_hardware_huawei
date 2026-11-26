package androidx.camera.extensions.impl;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Pair;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class HuaweiExtensionsHostTest {
    private HuaweiExtensionsHostTest() {}

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static List<PreviewExtenderImpl> createPreviewExtenders() {
        return Arrays.asList(
                new AutoPreviewExtenderImpl(),
                new HdrPreviewExtenderImpl(),
                new BokehPreviewExtenderImpl(),
                new NightPreviewExtenderImpl(),
                new BeautyPreviewExtenderImpl());
    }

    private static List<ImageCaptureExtenderImpl> createImageExtenders() {
        return Arrays.asList(
                new AutoImageCaptureExtenderImpl(),
                new HdrImageCaptureExtenderImpl(),
                new BokehImageCaptureExtenderImpl(),
                new NightImageCaptureExtenderImpl(),
                new BeautyImageCaptureExtenderImpl());
    }

    private static void testVersionContract() {
        ExtensionVersionImpl version = new ExtensionVersionImpl();
        assertTrue("1.4.0".equals(version.checkApiVersion("1.5.0")),
                "Expected checkApiVersion == 1.4.0");
        assertTrue(!version.isAdvancedExtenderImplemented(),
                "Expected isAdvancedExtenderImplemented() == false for Approach 2");
    }

    private static void testNullExecutorAndUnknownCameraIds() {
        AtomicInteger initCalls = new AtomicInteger(0);
        InitializerImpl.init("1.5.0", null,
                new InitializerImpl.OnExtensionsInitializedCallback() {
                    @Override
                    public void onSuccess() {
                        initCalls.incrementAndGet();
                    }

                    @Override
                    public void onFailure(int error) {
                        throw new AssertionError("Unexpected init failure: " + error);
                    }
                }, null);
        assertTrue(initCalls.get() == 1, "Expected init onSuccess() with null executor");

        AtomicInteger deinitCalls = new AtomicInteger(0);
        InitializerImpl.deinit(
                new InitializerImpl.OnExtensionsDeinitializedCallback() {
                    @Override
                    public void onSuccess() {
                        deinitCalls.incrementAndGet();
                    }

                    @Override
                    public void onFailure(int error) {
                        throw new AssertionError("Unexpected deinit failure: " + error);
                    }
                }, null);
        assertTrue(deinitCalls.get() == 1, "Expected deinit onSuccess() with null executor");

        CameraCharacteristics chars = new CameraCharacteristics();
        for (PreviewExtenderImpl ext : createPreviewExtenders()) {
            assertTrue(ext.isExtensionAvailable("0", chars),
                    ext.getClass().getSimpleName() + " must support camera 0");
            assertTrue(ext.isExtensionAvailable("1", chars),
                    ext.getClass().getSimpleName() + " must support camera 1");
            for (String badId : new String[]{"2", "", "external"}) {
                assertTrue(!ext.isExtensionAvailable(badId, chars),
                        ext.getClass().getSimpleName() + " must reject camera " + badId);
            }
            assertTrue(!ext.isExtensionAvailable(null, chars),
                    ext.getClass().getSimpleName() + " must reject null cameraId");
        }
        for (ImageCaptureExtenderImpl ext : createImageExtenders()) {
            assertTrue(ext.isExtensionAvailable("0", chars),
                    ext.getClass().getSimpleName() + " must support camera 0");
            assertTrue(ext.isExtensionAvailable("1", chars),
                    ext.getClass().getSimpleName() + " must support camera 1");
            for (String badId : new String[]{"2", "", "external"}) {
                assertTrue(!ext.isExtensionAvailable(badId, chars),
                        ext.getClass().getSimpleName() + " must reject camera " + badId);
            }
            assertTrue(!ext.isExtensionAvailable(null, chars),
                    ext.getClass().getSimpleName() + " must reject null cameraId");
        }
    }

    private static void testZeroCopyAndLifecycleInvariants() {
        CameraCharacteristics chars = new CameraCharacteristics();
        for (String camId : new String[]{"0", "1"}) {
            for (PreviewExtenderImpl ext : createPreviewExtenders()) {
                ext.init(camId, chars);
                ext.onInit(camId, chars, null);
                assertTrue(ext.getProcessorType()
                                == PreviewExtenderImpl.ProcessorType.PROCESSOR_TYPE_NONE,
                        "Expected PROCESSOR_TYPE_NONE");
                assertTrue(ext.getProcessor() == null, "Expected null preview processor");
                assertTrue(ext.onSessionType() == -1, "Expected onSessionType() == -1");
                assertTrue(ext.getSupportedResolutions() == null,
                        "Expected null getSupportedResolutions() (full stream table)");
                assertTrue(ext.onPresetSession() != null, "Expected non-null onPresetSession");
                assertTrue(ext.onEnableSession() != null, "Expected non-null onEnableSession");
                assertTrue(ext.onDisableSession() == null,
                        "Expected null onDisableSession to avoid spurious scene=0 capture on close");
                assertTrue(ext.getCaptureStage() != null, "Expected non-null getCaptureStage");
                ext.onDeInit();
            }
            for (ImageCaptureExtenderImpl ext : createImageExtenders()) {
                ext.init(camId, chars);
                ext.onInit(camId, chars, null);
                assertTrue(ext.onDisableSession() == null,
                        "Expected null ImageCapture onDisableSession");
                assertTrue(ext.getCaptureProcessor() == null,
                        "Expected null CaptureProcessorImpl for zero-copy hardware JPEG");
                assertTrue(ext.getMaxCaptureStage() == 1, "Expected getMaxCaptureStage() == 1");
                assertTrue(ext.getCaptureStages().size() == 1,
                        "Expected single CaptureStageImpl");
                assertTrue(!ext.isPostviewAvailable(), "Expected !isPostviewAvailable()");
                assertTrue(ext.getSupportedPostviewResolutions(null) == null,
                        "Expected null getSupportedPostviewResolutions");
                assertTrue(!ext.isCaptureProcessProgressAvailable(),
                        "Expected !isCaptureProcessProgressAvailable()");
                assertTrue(ext.getRealtimeCaptureLatency() == null,
                        "Expected null getRealtimeCaptureLatency()");
                assertTrue(ext.getSupportedResolutions() == null,
                        "Expected null getSupportedResolutions()");
                assertTrue(ext.onSessionType() == -1, "Expected onSessionType() == -1");
                ext.onDeInit();
            }
        }
    }

    private static void testVendorKeyJavaTypesMatchLiveTagDescriptor() {
        Map<String, Class<?>> expectedWireTypes = new HashMap<>();
        expectedWireTypes.put("com.huawei.capture.metadata.cameraSceneMode", Integer.class);
        expectedWireTypes.put("com.huawei.capture.metadata.cameraSessionSceneMode", Integer.class);
        expectedWireTypes.put("com.huawei.capture.metadata.apertureValue", Integer.class);
        expectedWireTypes.put("com.huawei.capture.metadata.faceBeautyLevel", Integer.class);
        expectedWireTypes.put("com.huawei.capture.metadata.imagePostProcessMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.postProcessForegroundReturnMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.extSceneMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.dualSensorMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.sensorHdrMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.apertureMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.opticalZoomMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.faceBeautyMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.normalFrontNightMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.portraitMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.frontVirtualEffectMode", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.frontVirtualEffectLevel", Byte.class);
        expectedWireTypes.put("com.huawei.capture.metadata.masterAIEnable", Byte.class);

        Set<String> seenTags = new HashSet<>();
        CameraCharacteristics chars = new CameraCharacteristics();

        for (String camId : new String[]{"0", "1"}) {
            for (PreviewExtenderImpl ext : createPreviewExtenders()) {
                ext.init(camId, chars);
                ext.onInit(camId, chars, null);
                for (CaptureStageImpl stage : Arrays.asList(
                        ext.onPresetSession(), ext.onEnableSession(), ext.getCaptureStage())) {
                    for (Pair<CaptureRequest.Key, Object> p : stage.getParameters()) {
                        String keyName = p.first.getName();
                        Class<?> want = expectedWireTypes.get(keyName);
                        assertTrue(want != null, "Unexpected vendor key: " + keyName);
                        assertTrue(want.isInstance(p.second),
                                "Wrong Java type for " + keyName + ": got "
                                        + p.second.getClass() + ", want " + want);
                        seenTags.add(keyName);
                    }
                }
                ext.onDeInit();
            }
            for (ImageCaptureExtenderImpl ext : createImageExtenders()) {
                // Before init (mCameraId == null in CameraExtensionsProxyService),
                // getAvailableCaptureRequestKeys/ResultKeys MUST contain zero com.huawei.* keys!
                for (CaptureRequest.Key k : ext.getAvailableCaptureRequestKeys()) {
                    assertTrue(!k.getName().startsWith("com.huawei."),
                            "Vendor key leaked into getAvailableCaptureRequestKeys: "
                                    + k.getName());
                }
                for (CaptureResult.Key k : ext.getAvailableCaptureResultKeys()) {
                    assertTrue(!k.getName().startsWith("com.huawei."),
                            "Vendor key leaked into getAvailableCaptureResultKeys: "
                                    + k.getName());
                }

                ext.init(camId, chars);
                ext.onInit(camId, chars, null);
                for (CaptureStageImpl stage : ext.getCaptureStages()) {
                    for (Pair<CaptureRequest.Key, Object> p : stage.getParameters()) {
                        String keyName = p.first.getName();
                        Class<?> want = expectedWireTypes.get(keyName);
                        assertTrue(want != null, "Unexpected vendor key: " + keyName);
                        assertTrue(want.isInstance(p.second),
                                "Wrong Java type for " + keyName + ": got "
                                        + p.second.getClass() + ", want " + want);
                        seenTags.add(keyName);
                    }
                    assertTrue(sawImagePostProcessMode(stage.getParameters()),
                            camId + " " + ext.getClass().getSimpleName()
                                    + " must enable imagePostProcessMode=1");
                    assertTrue(sawPostProcessFgReturnMode(stage.getParameters()),
                            camId + " " + ext.getClass().getSimpleName()
                                    + " must enable postProcessForegroundReturnMode=1");
                    if (ext instanceof BokehImageCaptureExtenderImpl) {
                        assertBokehContract(camId, stage.getParameters());
                    }
                    final boolean expectMasterAi =
                            "0".equals(camId) && (ext instanceof AutoImageCaptureExtenderImpl);
                    assertTrue(
                            hasByteTag(stage.getParameters(),
                                    "com.huawei.capture.metadata.masterAIEnable", (byte) 1)
                                    == expectMasterAi,
                            camId + " " + ext.getClass().getSimpleName()
                                    + " masterAIEnable expectation mismatch");
                }
                if ("1".equals(camId)) {
                    for (Pair<CaptureRequest.Key, Object> p :
                            ext.getCaptureStages().get(0).getParameters()) {
                        assertTrue(!"com.huawei.capture.metadata.dualSensorMode"
                                        .equals(p.first.getName()),
                                "Front camera 1 must never set dualSensorMode");
                    }
                }
                ext.onDeInit();
            }
        }
        assertTrue(seenTags.contains("com.huawei.capture.metadata.imagePostProcessMode"),
                "Expected imagePostProcessMode to be exercised");
        assertTrue(seenTags.contains("com.huawei.capture.metadata.postProcessForegroundReturnMode"),
                "Expected postProcessForegroundReturnMode to be exercised");
        assertTrue(seenTags.contains("com.huawei.capture.metadata.extSceneMode"),
                "Expected extSceneMode to be exercised");
        assertTrue(seenTags.contains("com.huawei.capture.metadata.dualSensorMode"),
                "Expected dualSensorMode to be exercised");
        assertTrue(seenTags.contains("com.huawei.capture.metadata.normalFrontNightMode"),
                "Expected normalFrontNightMode to be exercised");
        assertTrue(seenTags.contains("com.huawei.capture.metadata.frontVirtualEffectMode"),
                "Expected frontVirtualEffectMode to be exercised");
        assertTrue(seenTags.contains("com.huawei.capture.metadata.masterAIEnable"),
                "Expected masterAIEnable to be exercised");
    }

    private static boolean hasByteTag(
            List<Pair<CaptureRequest.Key, Object>> params, String name, byte val) {
        for (Pair<CaptureRequest.Key, Object> p : params) {
            if (name.equals(p.first.getName())
                    && p.second instanceof Byte
                    && ((Byte) p.second).byteValue() == val) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIntTag(
            List<Pair<CaptureRequest.Key, Object>> params, String name, int val) {
        for (Pair<CaptureRequest.Key, Object> p : params) {
            if (name.equals(p.first.getName())
                    && p.second instanceof Integer
                    && ((Integer) p.second).intValue() == val) {
                return true;
            }
        }
        return false;
    }

    private static boolean sawImagePostProcessMode(
            List<Pair<CaptureRequest.Key, Object>> params) {
        return hasByteTag(params, "com.huawei.capture.metadata.imagePostProcessMode", (byte) 1);
    }

    private static boolean sawPostProcessFgReturnMode(
            List<Pair<CaptureRequest.Key, Object>> params) {
        return hasByteTag(
                params, "com.huawei.capture.metadata.postProcessForegroundReturnMode", (byte) 1);
    }

    private static void assertBokehContract(
            String camId, List<Pair<CaptureRequest.Key, Object>> params) {
        if ("0".equals(camId)) {
            assertTrue(hasIntTag(params, "com.huawei.capture.metadata.cameraSceneMode", 19),
                    "Rear Bokeh must set cameraSceneMode=19");
            assertTrue(hasByteTag(params, "com.huawei.capture.metadata.apertureMode", (byte) 1),
                    "Rear Bokeh must set apertureMode=1");
            for (Pair<CaptureRequest.Key, Object> p : params) {
                assertTrue(!"com.huawei.capture.metadata.portraitMode".equals(p.first.getName()),
                        "Rear Bokeh (scene=19) must not set portraitMode");
            }
        } else {
            assertTrue(hasIntTag(params, "com.huawei.capture.metadata.cameraSceneMode", 23),
                    "Front Bokeh must set cameraSceneMode=23");
            assertTrue(hasByteTag(params, "com.huawei.capture.metadata.portraitMode", (byte) 1),
                    "Front Bokeh must set portraitMode=1");
        }
    }

    private static void testOrthogonalControlKeysPresent() {
        List<String> requiredNames = Arrays.asList(
                "android.control.zoomRatio",
                "android.scaler.cropRegion",
                "android.control.afMode",
                "android.control.afTrigger",
                "android.control.afRegions",
                "android.control.aeRegions",
                "android.control.awbRegions",
                "android.control.aeMode",
                "android.control.aePrecaptureTrigger",
                "android.flash.mode",
                "android.control.aeExposureCompensation");

        for (ImageCaptureExtenderImpl ext : createImageExtenders()) {
            Set<String> reqNames = new HashSet<>();
            for (CaptureRequest.Key k : ext.getAvailableCaptureRequestKeys()) {
                reqNames.add(k.getName());
            }
            for (String req : requiredNames) {
                assertTrue(reqNames.contains(req),
                        ext.getClass().getSimpleName() + " missing request key " + req);
            }
            assertTrue(!ext.getAvailableCaptureResultKeys().isEmpty(),
                    ext.getClass().getSimpleName() + " returned empty result keys");
        }
    }

    public static void main(String[] args) {
        testVersionContract();
        testNullExecutorAndUnknownCameraIds();
        testZeroCopyAndLifecycleInvariants();
        testVendorKeyJavaTypesMatchLiveTagDescriptor();
        testOrthogonalControlKeysPresent();
        System.out.println("ALL HUAWEI EXTENSIONS HOST TESTS PASSED");
    }
}
