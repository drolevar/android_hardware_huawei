#include "blanc_scene_policy.h"

#include <algorithm>
#include <cmath>

namespace blanc::camera {

namespace {

bool SetIfChanged(TagMap* tags, uint32_t tag_id, const TagValue& val) {
  auto it = tags->find(tag_id);
  if (it != tags->end() && it->second.type == val.type) {
    if (val.type == TagType::kByte && it->second.byte_val == val.byte_val) {
      return false;
    }
    if (val.type == TagType::kInt32 && it->second.int32_val == val.int32_val) {
      return false;
    }
    if (val.type == TagType::kFloat &&
        std::fabs(it->second.float_val - val.float_val) < 1e-6f) {
      return false;
    }
  }
  (*tags)[tag_id] = val;
  return true;
}

bool HasHighFps(const std::vector<int32_t>& ae_target_fps_range) {
  for (int32_t fps : ae_target_fps_range) {
    if (fps > 30) {
      return true;
    }
  }
  return false;
}

}  // namespace

SessionPolicy ClassifySession(int camera_id,
                              const std::vector<StreamDesc>& streams,
                              float active_array_width,
                              uint32_t operation_mode) {
  SessionPolicy policy;
  policy.camera_id = camera_id;
  policy.active_array_width =
      (std::isfinite(active_array_width) && active_array_width > 0.0f)
          ? active_array_width
          : 3968.0f;
  policy.last_zoom_ratio = 1.0f;
  if (operation_mode == kStreamConfigModeConstrainedHighSpeed) {
    policy.is_video = true;
  }

  for (const StreamDesc& s : streams) {
    if (s.format == kPixelFormatBlob) {
      policy.has_blob = true;
    }
    if (s.format == kPixelFormatRaw16 || s.format == kPixelFormatRawOpaque) {
      policy.has_raw16 = true;
    }
    if (s.format == kPixelFormatYCbCr420_888 &&
        (s.width > 1920U || s.height > 1440U)) {
      policy.has_high_res_yuv = true;
    }
    if ((s.usage & kBufferUsageVideoEncoder) != 0ULL) {
      policy.is_video = true;
    }
  }
  return policy;
}

float ComputeEffectiveZoom(std::optional<float> control_zoom_ratio,
                           const std::vector<int32_t>& scaler_crop_region,
                           float active_array_width) {
  float zoom = 1.0f;
  if (control_zoom_ratio.has_value() && std::isfinite(*control_zoom_ratio) &&
      *control_zoom_ratio >= 1.0f) {
    zoom = std::max(zoom, *control_zoom_ratio);
  }
  if (scaler_crop_region.size() >= 4 && std::isfinite(active_array_width) &&
      active_array_width > 0.0f) {
    const int32_t crop_w = scaler_crop_region[2];
    const int32_t crop_h = scaler_crop_region[3];
    if (crop_w > 0 && crop_h > 0) {
      const float crop_zoom = active_array_width / static_cast<float>(crop_w);
      if (std::isfinite(crop_zoom) && crop_zoom >= 1.0f) {
        zoom = std::max(zoom, crop_zoom);
      }
    }
  }
  return zoom;
}

bool RewriteMetadata(SessionPolicy* session,
                     MetadataOrigin origin,
                     bool is_empty_delta_buffer,
                     int32_t request_template,
                     const std::vector<int32_t>& ae_target_fps_range,
                     std::optional<float> control_zoom_ratio,
                     const std::vector<int32_t>& scaler_crop_region,
                     bool uses_hw_zoom_in_float,
                     TagMap* tags,
                     const VendorTagIds& tag_ids) {
  if (session == nullptr || tags == nullptr) {
    return false;
  }

  // Hard bypass for any RAW16 / RAW_OPAQUE session (GCam & Aperture RAW/RAW+JPEG).
  if (session->has_raw16) {
    return false;
  }

  // Preserve 0-byte repeating delta requests and 0-byte sessionParams verbatim
  // (matching stock hicam.hal.bz.so m_sessionSceneMode = -1 until frame 1).
  if (is_empty_delta_buffer) {
    return false;
  }

  // Video & >30 fps bypass: keep single-sensor throughput and VidHance EIS intact.
  if (session->is_video || request_template == kRequestTemplateVideoRecord ||
      request_template == kRequestTemplateVideoSnapshot ||
      HasHighFps(ae_target_fps_range)) {
    return false;
  }

  // Only rear Camera 0 and front Camera 1 are eligible for Huawei policy routing.
  if (session->camera_id != 0 && session->camera_id != 1) {
    return false;
  }

  bool mutated = false;
  int32_t effective_scene = -1;

  auto scene_it = tags->find(tag_ids.camera_scene_mode);
  if (scene_it != tags->end() && scene_it->second.type == TagType::kInt32 &&
      scene_it->second.int32_val >= 0 &&
      scene_it->second.int32_val != 10000) {
    effective_scene = scene_it->second.int32_val;
  } else if (origin == MetadataOrigin::kSessionParameters) {
    auto sess_it = tags->find(tag_ids.camera_session_scene_mode);
    if (sess_it != tags->end() && sess_it->second.type == TagType::kInt32 &&
        sess_it->second.int32_val >= 0 &&
        sess_it->second.int32_val != 10000) {
      effective_scene = sess_it->second.int32_val;
    }
  }

  const bool can_pp = session->can_postproc();

  if (session->camera_id == 0) {
    if (effective_scene < 0) {
      // Caller did not specify an explicit Huawei scene (or hit 10000 THIRD_PARTY):
      // upgrade to NORMAL RGB+Mono colorfusion.
      mutated |= SetIfChanged(tags, tag_ids.camera_session_scene_mode,
                              TagValue::Int32(0));
      mutated |=
          SetIfChanged(tags, tag_ids.camera_scene_mode, TagValue::Int32(0));
      mutated |= SetIfChanged(tags, tag_ids.dual_sensor_mode, TagValue::Byte(3));
      effective_scene = 0;
    }

    // Update tracked zoom when valid zoom metadata is present (or reset on new session).
    const bool has_valid_crop =
        scaler_crop_region.size() >= 4 && scaler_crop_region[2] > 0 &&
        scaler_crop_region[3] > 0;
    if (control_zoom_ratio.has_value() || has_valid_crop) {
      session->last_zoom_ratio = ComputeEffectiveZoom(
          control_zoom_ratio, scaler_crop_region, session->active_array_width);
    } else if (origin == MetadataOrigin::kSessionParameters) {
      session->last_zoom_ratio = 1.0f;
    }

    // Dynamic 1x <-> 2x+ hybrid zoom in zoom-compatible rear scenes
    // (0 = NORMAL/AUTO, 5 = HDR, 16 = BEAUTY, 19 = APERTURE_BOKEH).
    if (effective_scene == 0 || effective_scene == 5 ||
        effective_scene == 16 || effective_scene == 19) {
      const float effective_zoom = session->last_zoom_ratio;
      const uint8_t want_oz = (effective_zoom >= 1.99f) ? 1U : 0U;
      mutated |= SetIfChanged(tags, tag_ids.optical_zoom_mode,
                              TagValue::Byte(want_oz));
      if (want_oz == 1U) {
        mutated |=
            SetIfChanged(tags, tag_ids.dual_sensor_mode, TagValue::Byte(3));
      }
      if (uses_hw_zoom_in_float) {
        mutated |= SetIfChanged(tags, tag_ids.hw_zoom_in,
                                TagValue::Float(effective_zoom));
      }
    }
  } else {
    // Front Camera 1: never set dualSensorMode / opticalZoomMode / hw-zoom-in,
    // and only rewrite when direct-JPEG PPAlgo postproc is active.
    if (!can_pp) {
      return false;
    }
    if (effective_scene < 0) {
      mutated |= SetIfChanged(tags, tag_ids.camera_session_scene_mode,
                              TagValue::Int32(0));
      mutated |=
          SetIfChanged(tags, tag_ids.camera_scene_mode, TagValue::Int32(0));
      effective_scene = 0;
    }
  }

  // Enable direct-JPEG PPAlgo post-processing + technical scene detection for
  // eligible BLOB sessions on Camera 0 and Camera 1.
  if (can_pp) {
    mutated |= SetIfChanged(tags, tag_ids.image_post_process_mode,
                            TagValue::Byte(1));
    mutated |= SetIfChanged(tags, tag_ids.post_process_fg_return_mode,
                            TagValue::Byte(1));
    if (effective_scene == 0) {
      auto ext_it = tags->find(tag_ids.ext_scene_mode);
      if (ext_it == tags->end() ||
          (ext_it->second.type == TagType::kByte &&
           ext_it->second.byte_val == 0)) {
        mutated |= SetIfChanged(tags, tag_ids.ext_scene_mode,
                                TagValue::Byte(33));
      }
    }
  }

  return mutated;
}

}  // namespace blanc::camera
