#ifndef HUAWEI_BLANC_CAMERA_SCENE_POLICY_H_
#define HUAWEI_BLANC_CAMERA_SCENE_POLICY_H_

#include <cstdint>
#include <map>
#include <optional>
#include <vector>

namespace blanc::camera {

enum class TagType : uint8_t {
  kByte = 0,
  kInt32 = 1,
  kFloat = 2,
};

struct TagValue {
  TagType type = TagType::kInt32;
  uint8_t byte_val = 0;
  int32_t int32_val = 0;
  float float_val = 0.0f;

  static TagValue Byte(uint8_t v) {
    TagValue tv;
    tv.type = TagType::kByte;
    tv.byte_val = v;
    return tv;
  }

  static TagValue Int32(int32_t v) {
    TagValue tv;
    tv.type = TagType::kInt32;
    tv.int32_val = v;
    return tv;
  }

  static TagValue Float(float v) {
    TagValue tv;
    tv.type = TagType::kFloat;
    tv.float_val = v;
    return tv;
  }
};

using TagMap = std::map<uint32_t, TagValue>;

// PixelFormat & BufferUsage constants matching Android HIDL graphics/common.
constexpr int32_t kPixelFormatRaw16 = 0x20;
constexpr int32_t kPixelFormatBlob = 0x21;
constexpr int32_t kPixelFormatImplementationDefined = 0x22;
constexpr int32_t kPixelFormatYCbCr420_888 = 0x23;
constexpr int32_t kPixelFormatRawOpaque = 0x24;
constexpr uint64_t kBufferUsageVideoEncoder = 0x10000ULL;
constexpr uint32_t kStreamConfigModeConstrainedHighSpeed = 1U;

// ANDROID_CONTROL_CAPTURE_INTENT / RequestTemplate values.
constexpr int32_t kRequestTemplatePreview = 1;
constexpr int32_t kRequestTemplateStillCapture = 2;
constexpr int32_t kRequestTemplateVideoRecord = 3;
constexpr int32_t kRequestTemplateVideoSnapshot = 4;

// Verified EMUI 12 .225 Huawei vendor tag IDs (com.huawei.capture.metadata.*).
constexpr uint32_t kTagFaceBeautyMode = 0x8000001fU;
constexpr uint32_t kTagImagePostProcessMode = 0x8000002eU;
constexpr uint32_t kTagFaceBeautyLevel = 0x8000002fU;
constexpr uint32_t kTagDualSensorMode = 0x8000004bU;
constexpr uint32_t kTagHwZoomIn = 0x80000070U;
constexpr uint32_t kTagNormalFrontNightMode = 0x8000007aU;
constexpr uint32_t kTagExtSceneMode = 0x8000007bU;
constexpr uint32_t kTagNiceFoodMode = 0x8000008bU;
constexpr uint32_t kTagSensorHdrMode = 0x80000090U;
constexpr uint32_t kTagApertureMode = 0x800000c4U;
constexpr uint32_t kTagApertureValue = 0x800000c6U;
constexpr uint32_t kTagOpticalZoomMode = 0x800000caU;
constexpr uint32_t kTagPortraitMode = 0x80000264U;
constexpr uint32_t kTagFrontVirtualEffectMode = 0x80000265U;
constexpr uint32_t kTagFrontVirtualEffectLevel = 0x80000266U;
constexpr uint32_t kTagSmartSuggestHint = 0x8000028fU;
constexpr uint32_t kTagSmartSuggestEnterMode = 0x80000293U;
constexpr uint32_t kTagMasterAiEnable = 0x80000294U;
constexpr uint32_t kTagCameraSceneMode = 0x800002a2U;
constexpr uint32_t kTagCameraSessionSceneMode = 0x800002a3U;
constexpr uint32_t kTagPostProcessForegroundReturnMode = 0x800002e8U;

struct VendorTagIds {
  uint32_t camera_scene_mode = kTagCameraSceneMode;
  uint32_t camera_session_scene_mode = kTagCameraSessionSceneMode;
  uint32_t dual_sensor_mode = kTagDualSensorMode;
  uint32_t optical_zoom_mode = kTagOpticalZoomMode;
  uint32_t hw_zoom_in = kTagHwZoomIn;
  uint32_t image_post_process_mode = kTagImagePostProcessMode;
  uint32_t ext_scene_mode = kTagExtSceneMode;
  uint32_t post_process_fg_return_mode = kTagPostProcessForegroundReturnMode;
  uint32_t nice_food_mode = kTagNiceFoodMode;
  uint32_t smart_suggest_hint = kTagSmartSuggestHint;
  uint32_t smart_suggest_enter_mode = kTagSmartSuggestEnterMode;
  uint32_t master_ai_enable = kTagMasterAiEnable;
};

struct StreamDesc {
  int32_t format = 0;
  uint64_t usage = 0;
  uint32_t width = 0;
  uint32_t height = 0;
};

struct SessionPolicy {
  int camera_id = 0;
  bool has_blob = false;
  bool has_raw16 = false;
  bool has_high_res_yuv = false;
  bool is_video = false;
  float active_array_width = 3968.0f;
  float last_zoom_ratio = 1.0f;
  bool master_ai_enabled = false;
  int32_t master_ai_active_mode = 0;
  bool master_ai_state_dirty = false;

  bool can_postproc() const {
    return has_blob && !has_raw16 && !has_high_res_yuv && !is_video;
  }
};

enum class MetadataOrigin : uint8_t {
  kSessionParameters = 0,
  kCaptureRequest = 1,
};

SessionPolicy ClassifySession(int camera_id,
                              const std::vector<StreamDesc>& streams,
                              float active_array_width = 3968.0f,
                              uint32_t operation_mode = 0U);

float ComputeEffectiveZoom(std::optional<float> control_zoom_ratio,
                           const std::vector<int32_t>& scaler_crop_region,
                           float active_array_width);

int32_t MapSmartSuggestHintToEnterMode(int32_t raw_hint);

bool IsNiceFoodEnterMode(int32_t enter_mode);

bool ApplySmartSuggestHint(SessionPolicy* session, int32_t raw_hint);

bool RewriteMetadata(SessionPolicy* session,
                     MetadataOrigin origin,
                     bool is_empty_delta_buffer,
                     int32_t request_template,
                     const std::vector<int32_t>& ae_target_fps_range,
                     std::optional<float> control_zoom_ratio,
                     const std::vector<int32_t>& scaler_crop_region,
                     bool uses_hw_zoom_in_float,
                     TagMap* tags,
                     const VendorTagIds& tag_ids = VendorTagIds{});

}  // namespace blanc::camera

#endif  // HUAWEI_BLANC_CAMERA_SCENE_POLICY_H_
