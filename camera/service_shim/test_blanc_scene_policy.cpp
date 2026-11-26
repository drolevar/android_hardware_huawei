#include "blanc_scene_policy.h"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <optional>
#include <vector>

namespace {

using blanc::camera::ApplySmartSuggestHint;
using blanc::camera::ClassifySession;
using blanc::camera::ComputeEffectiveZoom;
using blanc::camera::IsNiceFoodEnterMode;
using blanc::camera::kBufferUsageVideoEncoder;
using blanc::camera::kPixelFormatBlob;
using blanc::camera::kPixelFormatImplementationDefined;
using blanc::camera::kPixelFormatRaw16;
using blanc::camera::kPixelFormatRawOpaque;
using blanc::camera::kPixelFormatYCbCr420_888;
using blanc::camera::kRequestTemplatePreview;
using blanc::camera::kRequestTemplateStillCapture;
using blanc::camera::kRequestTemplateVideoRecord;
using blanc::camera::kRequestTemplateVideoSnapshot;
using blanc::camera::kTagApertureMode;
using blanc::camera::kTagCameraSceneMode;
using blanc::camera::kTagCameraSessionSceneMode;
using blanc::camera::kTagDualSensorMode;
using blanc::camera::kTagExtSceneMode;
using blanc::camera::kTagFaceBeautyMode;
using blanc::camera::kTagHwZoomIn;
using blanc::camera::kTagImagePostProcessMode;
using blanc::camera::kTagMasterAiEnable;
using blanc::camera::kTagNiceFoodMode;
using blanc::camera::kTagOpticalZoomMode;
using blanc::camera::kTagPortraitMode;
using blanc::camera::kTagPostProcessForegroundReturnMode;
using blanc::camera::kTagSensorHdrMode;
using blanc::camera::kTagSmartSuggestEnterMode;
using blanc::camera::kTagSmartSuggestHint;
using blanc::camera::MapSmartSuggestHintToEnterMode;
using blanc::camera::MetadataOrigin;
using blanc::camera::RewriteMetadata;
using blanc::camera::SessionPolicy;
using blanc::camera::StreamDesc;
using blanc::camera::TagMap;
using blanc::camera::TagType;
using blanc::camera::TagValue;

void test_raw16_and_raw_opaque_hard_bypass() {
  for (int32_t raw_fmt : {kPixelFormatRaw16, kPixelFormatRawOpaque}) {
    std::vector<StreamDesc> streams = {
        {kPixelFormatImplementationDefined, 0, 1440, 1080},
        {kPixelFormatBlob, 0, 3968, 2976},
        {raw_fmt, 0, 3968, 2976},
    };
    SessionPolicy session = ClassifySession(0, streams, 3968.0f);
    assert(session.has_raw16);

    TagMap session_tags;
    bool mutated_session = RewriteMetadata(
        &session, MetadataOrigin::kSessionParameters, false,
        kRequestTemplateStillCapture, {15, 30}, 2.0f, {992, 744, 1984, 1488},
        true, &session_tags);
    assert(!mutated_session);
    assert(session_tags.empty());

    TagMap req_tags;
    bool mutated_req = RewriteMetadata(
        &session, MetadataOrigin::kCaptureRequest, false,
        kRequestTemplateStillCapture, {15, 30}, 2.0f, {992, 744, 1984, 1488},
        true, &req_tags);
    assert(!mutated_req);
    assert(req_tags.empty());
  }
}

void test_default_photo_injects_colorfusion_session_and_request() {
  std::vector<StreamDesc> streams = {
      {kPixelFormatImplementationDefined, 0, 1440, 1080},
      {kPixelFormatBlob, 0, 3968, 2976},
  };
  SessionPolicy session = ClassifySession(0, streams, 3968.0f);
  assert(session.has_blob);
  assert(!session.has_raw16);
  assert(!session.has_high_res_yuv);
  assert(!session.is_video);
  assert(session.can_postproc());

  // Absent scene mode on sessionParameters. Also test overwriting template defaults
  // (imagePostProcessMode == 0 and extSceneMode == 0).
  TagMap session_tags;
  session_tags[kTagImagePostProcessMode] = TagValue::Byte(0);
  session_tags[kTagExtSceneMode] = TagValue::Byte(0);
  bool mutated = RewriteMetadata(
      &session, MetadataOrigin::kSessionParameters, false,
      kRequestTemplatePreview, {15, 30}, std::nullopt, {0, 0, 3968, 2976},
      true, &session_tags);
  assert(mutated);
  assert(session_tags.at(kTagCameraSessionSceneMode).type == TagType::kInt32);
  assert(session_tags.at(kTagCameraSessionSceneMode).int32_val == 0);
  assert(session_tags.at(kTagCameraSceneMode).int32_val == 0);
  assert(session_tags.at(kTagDualSensorMode).type == TagType::kByte);
  assert(session_tags.at(kTagDualSensorMode).byte_val == 3);
  assert(session_tags.at(kTagOpticalZoomMode).type == TagType::kByte);
  assert(session_tags.at(kTagOpticalZoomMode).byte_val == 0);
  assert(session_tags.at(kTagImagePostProcessMode).type == TagType::kByte);
  assert(session_tags.at(kTagImagePostProcessMode).byte_val == 1);
  assert(session_tags.at(kTagPostProcessForegroundReturnMode).type == TagType::kByte);
  assert(session_tags.at(kTagPostProcessForegroundReturnMode).byte_val == 1);
  assert(session_tags.at(kTagExtSceneMode).type == TagType::kByte);
  assert(session_tags.at(kTagExtSceneMode).byte_val == 33);

  // -1 and 10000 (THIRD_PARTY) on capture requests also upgrade to colorfusion + PPAlgo.
  for (int32_t fallback_scene : {-1, 10000}) {
    TagMap req_tags;
    req_tags[kTagCameraSceneMode] = TagValue::Int32(fallback_scene);
    bool req_mutated = RewriteMetadata(
        &session, MetadataOrigin::kCaptureRequest, false,
        kRequestTemplateStillCapture, {15, 30}, 1.0f, {0, 0, 3968, 2976},
        true, &req_tags);
    assert(req_mutated);
    assert(req_tags.at(kTagCameraSessionSceneMode).int32_val == 0);
    assert(req_tags.at(kTagCameraSceneMode).int32_val == 0);
    assert(req_tags.at(kTagDualSensorMode).byte_val == 3);
    assert(req_tags.at(kTagOpticalZoomMode).byte_val == 0);
    assert(req_tags.at(kTagImagePostProcessMode).byte_val == 1);
    assert(req_tags.at(kTagPostProcessForegroundReturnMode).byte_val == 1);
    assert(req_tags.at(kTagExtSceneMode).byte_val == 33);
  }

  // Non-zero extSceneMode (e.g. 34 for HDR) is preserved; Aperture QR YUV (<= 1920x1080)
  // keeps can_postproc() == true.
  SessionPolicy qr_session = ClassifySession(
      0,
      {{kPixelFormatImplementationDefined, 0, 1440, 1080},
       {kPixelFormatYCbCr420_888, 0, 1280, 720},
       {kPixelFormatBlob, 0, 3968, 2976}},
      3968.0f);
  assert(qr_session.can_postproc());
  TagMap hdr_tags;
  hdr_tags[kTagCameraSceneMode] = TagValue::Int32(0);
  hdr_tags[kTagExtSceneMode] = TagValue::Byte(34);
  RewriteMetadata(&qr_session, MetadataOrigin::kCaptureRequest, false,
                  kRequestTemplateStillCapture, {15, 30}, 1.0f,
                  {0, 0, 3968, 2976}, true, &hdr_tags);
  assert(hdr_tags.at(kTagExtSceneMode).byte_val == 34);
  assert(hdr_tags.at(kTagImagePostProcessMode).byte_val == 1);
  assert(hdr_tags.at(kTagPostProcessForegroundReturnMode).byte_val == 1);
}

void test_explicit_extension_scene_preserved() {
  std::vector<StreamDesc> streams = {
      {kPixelFormatImplementationDefined, 0, 1440, 1080},
      {kPixelFormatBlob, 0, 3968, 2976},
  };
  for (int32_t ext_scene : {5, 7, 16, 19, 23}) {
    SessionPolicy session = ClassifySession(0, streams, 3968.0f);
    TagMap tags;
    tags[kTagCameraSessionSceneMode] = TagValue::Int32(ext_scene);
    tags[kTagCameraSceneMode] = TagValue::Int32(ext_scene);
    tags[kTagDualSensorMode] = TagValue::Byte(3);
    if (ext_scene == 5) {
      tags[kTagSensorHdrMode] = TagValue::Byte(1);
    } else if (ext_scene == 16) {
      tags[kTagFaceBeautyMode] = TagValue::Byte(1);
    } else if (ext_scene == 19 || ext_scene == 23) {
      tags[kTagApertureMode] = TagValue::Byte(1);
      tags[kTagPortraitMode] = TagValue::Byte(1);
    }

    RewriteMetadata(&session, MetadataOrigin::kCaptureRequest, false,
                    kRequestTemplateStillCapture, {15, 30}, 1.0f,
                    {0, 0, 3968, 2976}, true, &tags);
    assert(tags.at(kTagCameraSceneMode).int32_val == ext_scene);
    assert(tags.at(kTagCameraSessionSceneMode).int32_val == ext_scene);
    assert(tags.at(kTagDualSensorMode).byte_val == 3);
    if (ext_scene == 5) {
      assert(tags.at(kTagSensorHdrMode).byte_val == 1);
    } else if (ext_scene == 16) {
      assert(tags.at(kTagFaceBeautyMode).byte_val == 1);
    } else if (ext_scene == 19 || ext_scene == 23) {
      assert(tags.at(kTagApertureMode).byte_val == 1);
      assert(tags.at(kTagPortraitMode).byte_val == 1);
    }
  }
}

void test_hybrid_zoom_from_zoom_ratio_and_crop_region() {
  std::vector<StreamDesc> streams = {
      {kPixelFormatImplementationDefined, 0, 1440, 1080},
      {kPixelFormatBlob, 0, 3968, 2976},
  };
  for (int32_t scene : {0, 5, 16, 19}) {
    SessionPolicy session = ClassifySession(0, streams, 3968.0f);

    // 2.0x via CONTROL_ZOOM_RATIO.
    TagMap tags_ratio;
    if (scene != 0) {
      tags_ratio[kTagCameraSceneMode] = TagValue::Int32(scene);
    }
    bool m1 = RewriteMetadata(
        &session, MetadataOrigin::kCaptureRequest, false,
        kRequestTemplateStillCapture, {15, 30}, 2.0f, {0, 0, 3968, 2976},
        true, &tags_ratio);
    assert(m1);
    assert(tags_ratio.at(kTagOpticalZoomMode).byte_val == 1);
    assert(tags_ratio.at(kTagDualSensorMode).byte_val == 3);
    assert(tags_ratio.at(kTagHwZoomIn).type == TagType::kFloat);
    assert(std::fabs(tags_ratio.at(kTagHwZoomIn).float_val - 2.0f) < 1e-4f);

    // 2.0x via ZoomRatioMapper-translated SCALER_CROP_REGION (control_zoom_ratio absent).
    TagMap tags_crop;
    if (scene != 0) {
      tags_crop[kTagCameraSceneMode] = TagValue::Int32(scene);
    }
    bool m2 = RewriteMetadata(
        &session, MetadataOrigin::kCaptureRequest, false,
        kRequestTemplatePreview, {15, 30}, std::nullopt, {992, 744, 1984, 1488},
        true, &tags_crop);
    assert(m2);
    assert(tags_crop.at(kTagOpticalZoomMode).byte_val == 1);
    assert(tags_crop.at(kTagDualSensorMode).byte_val == 3);
    assert(std::fabs(tags_crop.at(kTagHwZoomIn).float_val - 2.0f) < 1e-4f);

    // Zoom back to 1.0x -> opticalZoomMode resets to 0.
    TagMap tags_back;
    if (scene != 0) {
      tags_back[kTagCameraSceneMode] = TagValue::Int32(scene);
    }
    bool m3 = RewriteMetadata(
        &session, MetadataOrigin::kCaptureRequest, false,
        kRequestTemplatePreview, {15, 30}, 1.0f, {0, 0, 3968, 2976},
        true, &tags_back);
    assert(m3);
    assert(tags_back.at(kTagOpticalZoomMode).byte_val == 0);
    assert(std::fabs(tags_back.at(kTagHwZoomIn).float_val - 1.0f) < 1e-4f);
  }

  // Night (7) stays at 1x without opticalZoomMode / hw-zoom-in mutation.
  SessionPolicy night_session = ClassifySession(0, streams, 3968.0f);
  TagMap night_tags;
  night_tags[kTagCameraSceneMode] = TagValue::Int32(7);
  RewriteMetadata(&night_session, MetadataOrigin::kCaptureRequest, false,
                  kRequestTemplateStillCapture, {15, 30}, 2.0f,
                  {992, 744, 1984, 1488}, true, &night_tags);
  assert(night_tags.count(kTagOpticalZoomMode) == 0);
  assert(night_tags.count(kTagHwZoomIn) == 0);
}

void test_empty_repeating_delta_settings_preserved() {
  std::vector<StreamDesc> streams = {
      {kPixelFormatImplementationDefined, 0, 1440, 1080},
      {kPixelFormatBlob, 0, 3968, 2976},
  };
  SessionPolicy session = ClassifySession(0, streams, 3968.0f);
  TagMap empty_delta;
  bool mutated = RewriteMetadata(
      &session, MetadataOrigin::kCaptureRequest, /*is_empty_delta_buffer=*/true,
      kRequestTemplatePreview, {}, std::nullopt, {}, true, &empty_delta);
  assert(!mutated);
  assert(empty_delta.empty());
}

void test_zero_or_malformed_crop_region_does_not_nan_or_zoom() {
  for (const auto& bad_crop : std::vector<std::vector<int32_t>>{
           {0, 0, 0, 0},
           {0, 0, -10, 100},
           {0, 0, 1984, 0},
           {10, 20},
           {},
       }) {
    float z = ComputeEffectiveZoom(std::nullopt, bad_crop, 3968.0f);
    assert(std::isfinite(z));
    assert(std::fabs(z - 1.0f) < 1e-6f);

    SessionPolicy session = ClassifySession(
        0, {{kPixelFormatImplementationDefined, 0, 1440, 1080}}, 3968.0f);
    TagMap tags;
    RewriteMetadata(&session, MetadataOrigin::kCaptureRequest, false,
                    kRequestTemplatePreview, {15, 30}, std::nullopt, bad_crop,
                    true, &tags);
    assert(tags.at(kTagOpticalZoomMode).byte_val == 0);
  }

  // Malformed {0, 0, 0, 0} crop must not reset a latched 2.0x zoom ratio.
  SessionPolicy latched = ClassifySession(
      0,
      {{kPixelFormatImplementationDefined, 0, 1440, 1080},
       {kPixelFormatBlob, 0, 3968, 2976}},
      3968.0f);
  TagMap z2_tags;
  RewriteMetadata(&latched, MetadataOrigin::kCaptureRequest, false,
                  kRequestTemplatePreview, {15, 30}, 2.0f,
                  {992, 744, 1984, 1488}, true, &z2_tags);
  assert(latched.last_zoom_ratio >= 1.99f);
  TagMap bad_after_z2;
  RewriteMetadata(&latched, MetadataOrigin::kCaptureRequest, false,
                  kRequestTemplateStillCapture, {15, 30}, std::nullopt,
                  {0, 0, 0, 0}, true, &bad_after_z2);
  assert(latched.last_zoom_ratio >= 1.99f);
  assert(bad_after_z2.at(kTagOpticalZoomMode).byte_val == 1);
}

void test_vendor_tag_wire_types_and_video_front_bypass() {
  // Exact wire types.
  SessionPolicy photo_session = ClassifySession(
      0,
      {{kPixelFormatImplementationDefined, 0, 1440, 1080},
       {kPixelFormatBlob, 0, 3968, 2976}},
      3968.0f);
  TagMap photo_tags;
  RewriteMetadata(&photo_session, MetadataOrigin::kCaptureRequest, false,
                  kRequestTemplateStillCapture, {15, 30}, 2.0f,
                  {992, 744, 1984, 1488}, true, &photo_tags);
  assert(photo_tags.at(kTagDualSensorMode).type == TagType::kByte);
  assert(photo_tags.at(kTagOpticalZoomMode).type == TagType::kByte);
  assert(photo_tags.at(kTagCameraSceneMode).type == TagType::kInt32);
  assert(photo_tags.at(kTagCameraSessionSceneMode).type == TagType::kInt32);
  assert(photo_tags.at(kTagHwZoomIn).type == TagType::kFloat);

  // Video stream usage (0x10000) bypass.
  SessionPolicy video_stream_session = ClassifySession(
      0,
      {{kPixelFormatImplementationDefined, kBufferUsageVideoEncoder, 1920, 1080}},
      3968.0f);
  assert(video_stream_session.is_video);
  TagMap vtags1;
  assert(!RewriteMetadata(&video_stream_session, MetadataOrigin::kCaptureRequest,
                          false, kRequestTemplatePreview, {30, 30}, 2.0f, {},
                          true, &vtags1));
  assert(vtags1.empty());

  // Video templates (VIDEO_RECORD / VIDEO_SNAPSHOT) bypass even without usage flag.
  for (int32_t tmpl : {kRequestTemplateVideoRecord, kRequestTemplateVideoSnapshot}) {
    SessionPolicy s = ClassifySession(
        0, {{kPixelFormatImplementationDefined, 0, 1920, 1080}}, 3968.0f);
    TagMap vtags2;
    assert(!RewriteMetadata(&s, MetadataOrigin::kCaptureRequest, false, tmpl,
                            {30, 30}, 2.0f, {}, true, &vtags2));
    assert(vtags2.empty());
  }

  // High FPS (> 30 fps) bypass.
  SessionPolicy hfps_session = ClassifySession(
      0, {{kPixelFormatImplementationDefined, 0, 1920, 1080}}, 3968.0f);
  TagMap hfps_tags;
  assert(!RewriteMetadata(&hfps_session, MetadataOrigin::kCaptureRequest, false,
                          kRequestTemplatePreview, {60, 60}, 2.0f, {}, true,
                          &hfps_tags));
  assert(hfps_tags.empty());

  // can_postproc() == false when BLOB is missing or high-res YUV is present:
  // imagePostProcessMode / postProcessForegroundReturnMode must not be injected.
  SessionPolicy preview_only = ClassifySession(
      0, {{kPixelFormatImplementationDefined, 0, 1440, 1080}}, 3968.0f);
  assert(!preview_only.can_postproc());
  TagMap preview_only_tags;
  RewriteMetadata(&preview_only, MetadataOrigin::kCaptureRequest, false,
                  kRequestTemplatePreview, {15, 30}, 1.0f, {}, true,
                  &preview_only_tags);
  assert(preview_only_tags.count(kTagImagePostProcessMode) == 0);
  assert(preview_only_tags.count(kTagPostProcessForegroundReturnMode) == 0);

  SessionPolicy high_res_yuv = ClassifySession(
      0,
      {{kPixelFormatImplementationDefined, 0, 1440, 1080},
       {kPixelFormatYCbCr420_888, 0, 3968, 2976},
       {kPixelFormatBlob, 0, 3968, 2976}},
      3968.0f);
  assert(high_res_yuv.has_high_res_yuv);
  assert(!high_res_yuv.can_postproc());
  TagMap high_res_yuv_tags;
  RewriteMetadata(&high_res_yuv, MetadataOrigin::kCaptureRequest, false,
                  kRequestTemplateStillCapture, {15, 30}, 1.0f, {}, true,
                  &high_res_yuv_tags);
  assert(high_res_yuv_tags.count(kTagImagePostProcessMode) == 0);
  assert(high_res_yuv_tags.count(kTagPostProcessForegroundReturnMode) == 0);

  // Front Camera 1: enables direct-JPEG PPAlgo + extSceneMode=33 when can_postproc(),
  // never receives dualSensorMode / opticalZoomMode / hw-zoom-in, and bypasses when
  // BLOB is missing, video, or RAW16.
  SessionPolicy front_session = ClassifySession(
      1,
      {{kPixelFormatImplementationDefined, 0, 1440, 1080},
       {kPixelFormatBlob, 0, 3264, 2448}},
      3264.0f);
  assert(front_session.can_postproc());
  TagMap front_tags;
  assert(RewriteMetadata(&front_session, MetadataOrigin::kCaptureRequest, false,
                         kRequestTemplateStillCapture, {15, 30}, 2.0f,
                         {816, 612, 1632, 1224}, true, &front_tags));
  assert(front_tags.count(kTagDualSensorMode) == 0);
  assert(front_tags.count(kTagOpticalZoomMode) == 0);
  assert(front_tags.count(kTagHwZoomIn) == 0);
  assert(front_tags.at(kTagCameraSceneMode).int32_val == 0);
  assert(front_tags.at(kTagCameraSessionSceneMode).int32_val == 0);
  assert(front_tags.at(kTagImagePostProcessMode).byte_val == 1);
  assert(front_tags.at(kTagPostProcessForegroundReturnMode).byte_val == 1);
  assert(front_tags.at(kTagExtSceneMode).byte_val == 33);

  SessionPolicy front_preview_only = ClassifySession(
      1, {{kPixelFormatImplementationDefined, 0, 1440, 1080}}, 3264.0f);
  TagMap front_prev_tags;
  assert(!RewriteMetadata(&front_preview_only, MetadataOrigin::kCaptureRequest,
                          false, kRequestTemplatePreview, {15, 30}, 1.0f, {},
                          true, &front_prev_tags));
  assert(front_prev_tags.empty());

  SessionPolicy front_video = ClassifySession(
      1,
      {{kPixelFormatImplementationDefined, kBufferUsageVideoEncoder, 1920, 1080},
       {kPixelFormatBlob, 0, 3264, 2448}},
      3264.0f);
  TagMap front_vid_tags;
  assert(!RewriteMetadata(&front_video, MetadataOrigin::kCaptureRequest, false,
                          kRequestTemplateVideoRecord, {30, 30}, 1.0f, {}, true,
                          &front_vid_tags));
  assert(front_vid_tags.empty());

  SessionPolicy front_raw = ClassifySession(
      1,
      {{kPixelFormatRaw16, 0, 3264, 2448},
       {kPixelFormatBlob, 0, 3264, 2448}},
      3264.0f);
  TagMap front_raw_tags;
  assert(!RewriteMetadata(&front_raw, MetadataOrigin::kCaptureRequest, false,
                          kRequestTemplateStillCapture, {15, 30}, 1.0f, {},
                          true, &front_raw_tags));
  assert(front_raw_tags.empty());
}

void test_high_speed_opmode_and_empty_session_params_preserved() {
  std::vector<StreamDesc> streams = {
      {kPixelFormatImplementationDefined, 0, 1920, 1080},
  };
  SessionPolicy hs_session = ClassifySession(
      0, streams, 3968.0f, blanc::camera::kStreamConfigModeConstrainedHighSpeed);
  assert(hs_session.is_video);

  SessionPolicy normal_session = ClassifySession(0, streams, 3968.0f, 0U);
  assert(!normal_session.is_video);
  TagMap empty_sess_params;
  bool mutated = RewriteMetadata(
      &normal_session, MetadataOrigin::kSessionParameters,
      /*is_empty_delta_buffer=*/true, kRequestTemplatePreview, {},
      std::nullopt, {}, true, &empty_sess_params);
  assert(!mutated);
  assert(empty_sess_params.empty());
}

void test_master_ai_closed_loop_auto_only() {
  std::vector<StreamDesc> rear_streams = {
      {kPixelFormatImplementationDefined, 0, 1440, 1080},
      {kPixelFormatBlob, 0, 3968, 2976},
  };
  SessionPolicy session = ClassifySession(0, rear_streams, 3968.0f);
  assert(!session.master_ai_enabled);
  assert(session.master_ai_active_mode == 0);
  assert(!session.master_ai_state_dirty);

  // ExtensionMode.AUTO on Rear Camera 0 opts in with masterAIEnable=1.
  TagMap req1;
  req1[kTagMasterAiEnable] = TagValue::Byte(1);
  assert(RewriteMetadata(&session, MetadataOrigin::kCaptureRequest, false,
                         kRequestTemplatePreview, {15, 30}, 1.0f, {}, true,
                         &req1));
  assert(session.master_ai_enabled);
  assert(req1.at(kTagSmartSuggestEnterMode).type == TagType::kInt32);
  assert(req1.at(kTagSmartSuggestEnterMode).int32_val == 0);
  assert(req1.at(kTagNiceFoodMode).type == TagType::kByte);
  assert(req1.at(kTagNiceFoodMode).byte_val == 0);
  assert(!session.master_ai_state_dirty);

  // Food hints (7, 115, and 0x40007 with assist flag) set enter_mode & niceFoodMode=1.
  for (int32_t hint : {7, 115, 0x40007}) {
    const int32_t expected_mode = (hint == 115) ? 115 : 7;
    if (session.master_ai_active_mode == expected_mode) {
      assert(!ApplySmartSuggestHint(&session, hint));
      continue;
    }
    assert(ApplySmartSuggestHint(&session, hint));
    assert(session.master_ai_active_mode == expected_mode);
    assert(session.master_ai_state_dirty);
    TagMap req;
    req[kTagMasterAiEnable] = TagValue::Byte(1);
    assert(RewriteMetadata(&session, MetadataOrigin::kCaptureRequest, false,
                           kRequestTemplatePreview, {15, 30}, 1.0f, {}, true,
                           &req));
    assert(req.at(kTagSmartSuggestEnterMode).int32_val == expected_mode);
    assert(req.at(kTagNiceFoodMode).byte_val == 1);
    assert(!session.master_ai_state_dirty);
  }

  // Vivid / BlueSky / Greenery / Sunset / Flower hints keep niceFoodMode=0.
  for (int32_t vivid_mode : {12, 13, 15, 9}) {
    assert(ApplySmartSuggestHint(&session, vivid_mode | 0x40000));
    assert(session.master_ai_state_dirty);
    TagMap req;
    req[kTagMasterAiEnable] = TagValue::Byte(1);
    assert(RewriteMetadata(&session, MetadataOrigin::kCaptureRequest, false,
                           kRequestTemplatePreview, {15, 30}, 1.0f, {}, true,
                           &req));
    assert(req.at(kTagSmartSuggestEnterMode).int32_val == vivid_mode);
    assert(req.at(kTagNiceFoodMode).byte_val == 0);
    assert(!session.master_ai_state_dirty);
  }

  // Non-whitelisted hints (2, 17, 18) and explicit exit (0) return to mode 0.
  for (int32_t exit_hint : {2, 17, 18, 0}) {
    assert(MapSmartSuggestHintToEnterMode(exit_hint) == 0);
  }
  assert(ApplySmartSuggestHint(&session, 17));
  assert(session.master_ai_active_mode == 0);
  assert(session.master_ai_state_dirty);
  TagMap req_exit;
  req_exit[kTagMasterAiEnable] = TagValue::Byte(1);
  assert(RewriteMetadata(&session, MetadataOrigin::kCaptureRequest, false,
                         kRequestTemplatePreview, {15, 30}, 1.0f, {}, true,
                         &req_exit));
  assert(req_exit.at(kTagSmartSuggestEnterMode).int32_val == 0);
  assert(req_exit.at(kTagNiceFoodMode).byte_val == 0);
  assert(!session.master_ai_state_dirty);

  // Re-classifying resets Master AI state.
  assert(ApplySmartSuggestHint(&session, 7));
  session = ClassifySession(0, rear_streams, 3968.0f);
  assert(!session.master_ai_enabled);
  assert(session.master_ai_active_mode == 0);
  assert(!session.master_ai_state_dirty);

  // Photo (NONE) without masterAIEnable stays off and ignores hints.
  TagMap photo_req;
  assert(RewriteMetadata(&session, MetadataOrigin::kCaptureRequest, false,
                         kRequestTemplatePreview, {15, 30}, 1.0f, {}, true,
                         &photo_req));
  assert(!session.master_ai_enabled);
  assert(photo_req.count(kTagSmartSuggestEnterMode) == 0);
  assert(photo_req.count(kTagNiceFoodMode) == 0);
  assert(!ApplySmartSuggestHint(&session, 7));

  // Explicit HDR (5), BOKEH (19), NIGHT (36), FACE_RETOUCH (16) stay unaffected.
  for (int32_t explicit_scene : {5, 19, 36, 16}) {
    SessionPolicy exp_sess = ClassifySession(0, rear_streams, 3968.0f);
    assert(ApplySmartSuggestHint(&exp_sess, 7) == false);
    TagMap exp_req;
    exp_req[kTagCameraSceneMode] = TagValue::Int32(explicit_scene);
    exp_req[kTagMasterAiEnable] = TagValue::Byte(1);
    RewriteMetadata(&exp_sess, MetadataOrigin::kCaptureRequest, false,
                    kRequestTemplatePreview, {15, 30}, 1.0f, {}, true, &exp_req);
    assert(!exp_sess.master_ai_enabled);
    assert(exp_req.count(kTagSmartSuggestEnterMode) == 0);
    assert(!ApplySmartSuggestHint(&exp_sess, 7));
  }

  // Front Camera 1, Video, and RAW16 reject Master AI opt-in and ignore hints.
  SessionPolicy front_sess = ClassifySession(
      1, {{kPixelFormatImplementationDefined, 0, 1440, 1080},
          {kPixelFormatBlob, 0, 3264, 2448}},
      3264.0f);
  TagMap front_req;
  front_req[kTagMasterAiEnable] = TagValue::Byte(1);
  RewriteMetadata(&front_sess, MetadataOrigin::kCaptureRequest, false,
                  kRequestTemplatePreview, {15, 30}, 1.0f, {}, true, &front_req);
  assert(!front_sess.master_ai_enabled);
  assert(front_req.count(kTagSmartSuggestEnterMode) == 0);
  assert(!ApplySmartSuggestHint(&front_sess, 7));

  SessionPolicy video_sess = ClassifySession(
      0,
      {{kPixelFormatImplementationDefined, kBufferUsageVideoEncoder, 1920, 1080},
       {kPixelFormatBlob, 0, 3968, 2976}},
      3968.0f);
  TagMap vid_req;
  vid_req[kTagMasterAiEnable] = TagValue::Byte(1);
  assert(!RewriteMetadata(&video_sess, MetadataOrigin::kCaptureRequest, false,
                          kRequestTemplateVideoRecord, {30, 30}, 1.0f, {}, true,
                          &vid_req));
  assert(!video_sess.master_ai_enabled);
  assert(!ApplySmartSuggestHint(&video_sess, 7));

  SessionPolicy raw_sess = ClassifySession(
      0, {{kPixelFormatRaw16, 0, 3968, 2976}, {kPixelFormatBlob, 0, 3968, 2976}},
      3968.0f);
  TagMap raw_req;
  raw_req[kTagMasterAiEnable] = TagValue::Byte(1);
  assert(!RewriteMetadata(&raw_sess, MetadataOrigin::kCaptureRequest, false,
                          kRequestTemplateStillCapture, {15, 30}, 1.0f, {},
                          true, &raw_req));
  assert(!raw_sess.master_ai_enabled);
  assert(!ApplySmartSuggestHint(&raw_sess, 7));
}

}  // namespace

int main() {
  test_raw16_and_raw_opaque_hard_bypass();
  test_default_photo_injects_colorfusion_session_and_request();
  test_explicit_extension_scene_preserved();
  test_hybrid_zoom_from_zoom_ratio_and_crop_region();
  test_empty_repeating_delta_settings_preserved();
  test_zero_or_malformed_crop_region_does_not_nan_or_zoom();
  test_vendor_tag_wire_types_and_video_front_bypass();
  test_high_speed_opmode_and_empty_session_params_preserved();
  test_master_ai_closed_loop_auto_only();
  std::printf("ALL 9 POLICY TESTS PASSED\n");
  return 0;
}
