//
// SPDX-FileCopyrightText: 2026 The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

#define LOG_TAG "BlancHalServiceShim"

#include "blanc_scene_policy.h"

#include <android/hardware/camera/common/1.0/types.h>
#include <android/hardware/camera/device/1.0/ICameraDevice.h>
#include <android/hardware/camera/device/3.2/ICameraDevice.h>
#include <android/hardware/camera/device/3.2/ICameraDeviceCallback.h>
#include <android/hardware/camera/device/3.2/ICameraDeviceSession.h>
#include <android/hardware/camera/device/3.3/ICameraDeviceSession.h>
#include <android/hardware/camera/device/3.4/ICameraDeviceSession.h>
#include <android/hardware/camera/provider/2.4/ICameraProvider.h>
#include <android/hardware/camera/provider/2.4/ICameraProviderCallback.h>
#include <android/hardware/camera/provider/2.5/ICameraProvider.h>
#include <dlfcn.h>
#include <fmq/MessageQueue.h>
#include <hidl/MQDescriptor.h>
#include <log/log.h>
#include <system/camera_metadata.h>
#include <system/camera_vendor_tags.h>

#include <charconv>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

extern "C" int set_camera_metadata_vendor_ops(const vendor_tag_ops_t* ops);

namespace {

using ::android::sp;
using ::android::hardware::hidl_handle;
using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;
using ::android::hardware::kSynchronizedReadWrite;
using ::android::hardware::MessageQueue;
using ::android::hardware::MQDescriptorSync;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::camera::common::V1_0::CameraMetadataType;
using ::android::hardware::camera::common::V1_0::CameraResourceCost;
using ::android::hardware::camera::common::V1_0::Status;
using ::android::hardware::camera::common::V1_0::TorchMode;
using ::android::hardware::camera::common::V1_0::VendorTagSection;
using ::blanc::camera::ClassifySession;
using ::blanc::camera::MetadataOrigin;
using ::blanc::camera::RewriteMetadata;
using ::blanc::camera::SessionPolicy;
using ::blanc::camera::StreamDesc;
using ::blanc::camera::TagMap;
using ::blanc::camera::TagType;
using ::blanc::camera::TagValue;
using ::blanc::camera::VendorTagIds;

namespace V1_0_Dev = ::android::hardware::camera::device::V1_0;
namespace V3_2 = ::android::hardware::camera::device::V3_2;
namespace V3_3 = ::android::hardware::camera::device::V3_3;
namespace V3_4 = ::android::hardware::camera::device::V3_4;
namespace V2_4 = ::android::hardware::camera::provider::V2_4;
namespace V2_5 = ::android::hardware::camera::provider::V2_5;

using RequestMetadataQueue = MessageQueue<uint8_t, kSynchronizedReadWrite>;
constexpr size_t kDefaultRequestFmqBytes = 1ULL << 20;  // 1 MiB

// Fallback vendor_tag_ops registry in case libcamera_metadata in this linker
// namespace has not yet had vendor_tag_ops installed when we add vendor entries.
struct StoredVendorTag {
  uint32_t tag_id = 0;
  std::string section;
  std::string name;
  int type = -1;
};

std::mutex g_vendor_ops_mutex;
std::vector<uint32_t> g_all_vendor_tag_ids;
std::unordered_map<uint32_t, StoredVendorTag> g_vendor_tag_lookup;
bool g_vendor_ops_installed = false;

int FallbackGetTagCount(const vendor_tag_ops_t*) {
  std::lock_guard<std::mutex> lock(g_vendor_ops_mutex);
  return static_cast<int>(g_all_vendor_tag_ids.size());
}

void FallbackGetAllTags(const vendor_tag_ops_t*, uint32_t* tag_array) {
  if (tag_array == nullptr) {
    return;
  }
  std::lock_guard<std::mutex> lock(g_vendor_ops_mutex);
  if (!g_all_vendor_tag_ids.empty()) {
    std::memcpy(tag_array, g_all_vendor_tag_ids.data(),
                g_all_vendor_tag_ids.size() * sizeof(uint32_t));
  }
}

const char* FallbackGetSectionName(const vendor_tag_ops_t*, uint32_t tag) {
  std::lock_guard<std::mutex> lock(g_vendor_ops_mutex);
  auto it = g_vendor_tag_lookup.find(tag);
  return (it != g_vendor_tag_lookup.end()) ? it->second.section.c_str()
                                           : nullptr;
}

const char* FallbackGetTagName(const vendor_tag_ops_t*, uint32_t tag) {
  std::lock_guard<std::mutex> lock(g_vendor_ops_mutex);
  auto it = g_vendor_tag_lookup.find(tag);
  return (it != g_vendor_tag_lookup.end()) ? it->second.name.c_str() : nullptr;
}

int FallbackGetTagType(const vendor_tag_ops_t*, uint32_t tag) {
  std::lock_guard<std::mutex> lock(g_vendor_ops_mutex);
  auto it = g_vendor_tag_lookup.find(tag);
  return (it != g_vendor_tag_lookup.end()) ? it->second.type : -1;
}

const vendor_tag_ops_t g_fallback_vendor_tag_ops = {
    .get_tag_count = FallbackGetTagCount,
    .get_all_tags = FallbackGetAllTags,
    .get_section_name = FallbackGetSectionName,
    .get_tag_name = FallbackGetTagName,
    .get_tag_type = FallbackGetTagType,
    .reserved = {nullptr},
};

void PopulateVendorTagsFromSections(const hidl_vec<VendorTagSection>& sections,
                                    VendorTagIds* out_ids) {
  VendorTagIds resolved;
  {
    std::lock_guard<std::mutex> lock(g_vendor_ops_mutex);
    g_all_vendor_tag_ids.clear();
    g_vendor_tag_lookup.clear();
    for (const auto& sec : sections) {
      const std::string sec_name = sec.sectionName.c_str();
      for (const auto& tag : sec.tags) {
        StoredVendorTag st;
        st.tag_id = tag.tagId;
        st.section = sec_name;
        st.name = tag.tagName.c_str();
        st.type = static_cast<int>(tag.tagType);
        g_all_vendor_tag_ids.push_back(st.tag_id);
        g_vendor_tag_lookup[st.tag_id] = std::move(st);

        if (sec_name == "com.huawei.capture.metadata") {
          const std::string tag_name = tag.tagName.c_str();
          if (tag_name == "cameraSceneMode") {
            resolved.camera_scene_mode = tag.tagId;
          } else if (tag_name == "cameraSessionSceneMode") {
            resolved.camera_session_scene_mode = tag.tagId;
          } else if (tag_name == "dualSensorMode") {
            resolved.dual_sensor_mode = tag.tagId;
          } else if (tag_name == "opticalZoomMode") {
            resolved.optical_zoom_mode = tag.tagId;
          } else if (tag_name == "hw-zoom-in") {
            resolved.hw_zoom_in = tag.tagId;
          }
        }
      }
    }
  }
  if (out_ids != nullptr) {
    *out_ids = resolved;
  }
  if (get_camera_metadata_tag_type(resolved.camera_scene_mode) == -1) {
    std::lock_guard<std::mutex> lock(g_vendor_ops_mutex);
    if (!g_vendor_ops_installed && !g_all_vendor_tag_ids.empty()) {
      if (set_camera_metadata_vendor_ops(&g_fallback_vendor_tag_ops) == 0) {
        g_vendor_ops_installed = true;
        ALOGI("Installed fallback vendor_tag_ops (%zu Huawei vendor tags)",
              g_all_vendor_tag_ids.size());
      }
    }
  }
}

int ParseCameraId(const hidl_string& device_name) {
  const std::string s(device_name.c_str());
  const size_t slash = s.rfind('/');
  const char* start = (slash == std::string::npos) ? s.c_str()
                                                   : (s.c_str() + slash + 1);
  const char* end = s.c_str() + s.size();
  if (start >= end) {
    return -1;
  }
  int id = -1;
  auto [ptr, ec] = std::from_chars(start, end, id);
  if (ec != std::errc() || ptr != end) {
    return -1;
  }
  return id;
}

void CopyStreamBufferShallow(const V3_2::StreamBuffer& src,
                             V3_2::StreamBuffer* dst) {
  dst->streamId = src.streamId;
  dst->bufferId = src.bufferId;
  // Assign const native_handle_t* directly so hidl_handle does NOT clone/own FDs.
  dst->buffer = src.buffer.getNativeHandle();
  dst->status = src.status;
  dst->acquireFence = src.acquireFence.getNativeHandle();
  dst->releaseFence = src.releaseFence.getNativeHandle();
}

void ReadEntryIntoTagMap(const camera_metadata_t* meta,
                         uint32_t tag_id,
                         TagMap* out_tags) {
  camera_metadata_ro_entry_t entry{};
  if (find_camera_metadata_ro_entry(meta, tag_id, &entry) != 0 ||
      entry.count == 0) {
    return;
  }
  if (entry.type == TYPE_BYTE) {
    (*out_tags)[tag_id] = TagValue::Byte(entry.data.u8[0]);
  } else if (entry.type == TYPE_INT32) {
    (*out_tags)[tag_id] = TagValue::Int32(entry.data.i32[0]);
  } else if (entry.type == TYPE_FLOAT) {
    (*out_tags)[tag_id] = TagValue::Float(entry.data.f[0]);
  }
}

bool UpsertMetadataEntry(camera_metadata_t* dst,
                         uint32_t tag_id,
                         const TagValue& tv) {
  const void* data_ptr = nullptr;
  if (tv.type == TagType::kByte) {
    data_ptr = &tv.byte_val;
  } else if (tv.type == TagType::kInt32) {
    data_ptr = &tv.int32_val;
  } else if (tv.type == TagType::kFloat) {
    data_ptr = &tv.float_val;
  } else {
    return false;
  }

  camera_metadata_entry_t existing{};
  if (find_camera_metadata_entry(dst, tag_id, &existing) == 0) {
    return update_camera_metadata_entry(dst, existing.index, data_ptr, 1,
                                        nullptr) == 0;
  }
  return add_camera_metadata_entry(dst, tag_id, data_ptr, 1) == 0;
}

bool RewriteMetadataBytes(SessionPolicy* session,
                          MetadataOrigin origin,
                          const uint8_t* src_bytes,
                          size_t src_len,
                          const VendorTagIds& tag_ids,
                          std::vector<uint8_t>* out_rewritten) {
  if (session == nullptr || out_rewritten == nullptr) {
    return false;
  }
  if (session->has_raw16 || session->is_video || session->camera_id != 0) {
    return false;
  }

  if (src_len == 0 || src_bytes == nullptr) {
    if (origin == MetadataOrigin::kCaptureRequest) {
      TagMap dummy;
      return RewriteMetadata(session, origin, /*is_empty_delta_buffer=*/true,
                             blanc::camera::kRequestTemplatePreview, {},
                             std::nullopt, {}, /*uses_hw_zoom_in_float=*/true,
                             &dummy, tag_ids);
    }
    // Empty sessionParams on Camera 0 still/preview session: synthesize initial
    // colorfusion sessionParameters so HISP160 starts in scene 0 + dualSensorMode 3.
    TagMap tags;
    if (!RewriteMetadata(session, MetadataOrigin::kSessionParameters,
                         /*is_empty_delta_buffer=*/false,
                         blanc::camera::kRequestTemplatePreview, {},
                         std::nullopt, {}, /*uses_hw_zoom_in_float=*/true,
                         &tags, tag_ids)) {
      return false;
    }
    camera_metadata_t* dst = allocate_camera_metadata(tags.size() + 4, 128);
    if (dst == nullptr) {
      return false;
    }
    bool ok = true;
    for (const auto& [tid, tv] : tags) {
      ok &= UpsertMetadataEntry(dst, tid, tv);
    }
    if (!ok) {
      free_camera_metadata(dst);
      return false;
    }
    sort_camera_metadata(dst);
    const size_t compact_sz = get_camera_metadata_compact_size(dst);
    out_rewritten->resize(compact_sz);
    if (copy_camera_metadata(out_rewritten->data(), compact_sz, dst) ==
        nullptr) {
      free_camera_metadata(dst);
      out_rewritten->clear();
      return false;
    }
    free_camera_metadata(dst);
    return true;
  }

  const auto* raw_meta = reinterpret_cast<const camera_metadata_t*>(src_bytes);
  const int val_res = validate_camera_metadata_structure(raw_meta, &src_len);
  if (val_res == CAMERA_METADATA_VALIDATION_ERROR) {
    ALOGW("Skipping malformed camera_metadata packet (%zu bytes)", src_len);
    return false;
  }

  camera_metadata_t* aligned_clone = nullptr;
  const camera_metadata_t* meta = raw_meta;
  if (val_res == CAMERA_METADATA_VALIDATION_SHIFTED) {
    aligned_clone = clone_camera_metadata(raw_meta);
    if (aligned_clone == nullptr) {
      return false;
    }
    meta = aligned_clone;
  }

  int32_t request_template = blanc::camera::kRequestTemplatePreview;
  camera_metadata_ro_entry_t entry{};
  if (find_camera_metadata_ro_entry(meta, ANDROID_CONTROL_CAPTURE_INTENT,
                                    &entry) == 0 &&
      entry.count > 0) {
    request_template = static_cast<int32_t>(entry.data.u8[0]);
  }

  std::vector<int32_t> ae_fps;
  if (find_camera_metadata_ro_entry(meta, ANDROID_CONTROL_AE_TARGET_FPS_RANGE,
                                    &entry) == 0 &&
      entry.count > 0) {
    ae_fps.assign(entry.data.i32, entry.data.i32 + entry.count);
  }

  std::optional<float> control_zoom_ratio;
  if (find_camera_metadata_ro_entry(meta, ANDROID_CONTROL_ZOOM_RATIO, &entry) ==
          0 &&
      entry.count > 0) {
    control_zoom_ratio = entry.data.f[0];
  }

  std::vector<int32_t> crop_region;
  if (find_camera_metadata_ro_entry(meta, ANDROID_SCALER_CROP_REGION, &entry) ==
          0 &&
      entry.count > 0) {
    crop_region.assign(entry.data.i32, entry.data.i32 + entry.count);
  }

  TagMap tags;
  ReadEntryIntoTagMap(meta, tag_ids.camera_scene_mode, &tags);
  ReadEntryIntoTagMap(meta, tag_ids.camera_session_scene_mode, &tags);
  ReadEntryIntoTagMap(meta, tag_ids.dual_sensor_mode, &tags);
  ReadEntryIntoTagMap(meta, tag_ids.optical_zoom_mode, &tags);
  ReadEntryIntoTagMap(meta, tag_ids.hw_zoom_in, &tags);

  const bool mutated = RewriteMetadata(
      session, origin, /*is_empty_delta_buffer=*/false, request_template,
      ae_fps, control_zoom_ratio, crop_region, /*uses_hw_zoom_in_float=*/true,
      &tags, tag_ids);
  if (!mutated) {
    if (aligned_clone != nullptr) {
      free_camera_metadata(aligned_clone);
    }
    return false;
  }

  camera_metadata_t* dst = allocate_camera_metadata(
      get_camera_metadata_entry_count(meta) + tags.size() + 4,
      get_camera_metadata_data_count(meta) + 128);
  if (dst == nullptr) {
    if (aligned_clone != nullptr) {
      free_camera_metadata(aligned_clone);
    }
    return false;
  }

  if (append_camera_metadata(dst, meta) != 0) {
    free_camera_metadata(dst);
    if (aligned_clone != nullptr) {
      free_camera_metadata(aligned_clone);
    }
    return false;
  }
  if (aligned_clone != nullptr) {
    free_camera_metadata(aligned_clone);
    aligned_clone = nullptr;
  }

  for (const auto& [tid, tv] : tags) {
    if (!UpsertMetadataEntry(dst, tid, tv)) {
      ALOGW("Failed to upsert vendor tag 0x%08x", tid);
      free_camera_metadata(dst);
      return false;
    }
  }
  sort_camera_metadata(dst);

  const size_t compact_sz = get_camera_metadata_compact_size(dst);
  out_rewritten->resize(compact_sz);
  if (copy_camera_metadata(out_rewritten->data(), compact_sz, dst) == nullptr) {
    free_camera_metadata(dst);
    out_rewritten->clear();
    return false;
  }
  free_camera_metadata(dst);
  return true;
}

class BlancCameraDeviceSession final : public V3_4::ICameraDeviceSession {
 public:
  BlancCameraDeviceSession(sp<V3_2::ICameraDeviceSession> real_3_2,
                           sp<V3_3::ICameraDeviceSession> real_3_3,
                           sp<V3_4::ICameraDeviceSession> real_3_4,
                           int camera_id,
                           float active_array_width,
                           const VendorTagIds& tag_ids)
      : real_3_2_(std::move(real_3_2)),
        real_3_3_(std::move(real_3_3)),
        real_3_4_(std::move(real_3_4)),
        camera_id_(camera_id),
        active_array_width_(active_array_width),
        tag_ids_(tag_ids) {
    session_ = ClassifySession(camera_id_, {}, active_array_width_);
    outer_req_fmq_ = std::make_unique<RequestMetadataQueue>(
        kDefaultRequestFmqBytes, /*configureEventFlagWord=*/false);
    if (!outer_req_fmq_->isValid()) {
      ALOGE("Failed to allocate outer RequestMetadataQueue");
      outer_req_fmq_.reset();
    }
    real_3_2_->getCaptureRequestMetadataQueue(
        [this](const MQDescriptorSync<uint8_t>& desc) {
          auto q = std::make_unique<RequestMetadataQueue>(desc);
          if (q->isValid()) {
            inner_req_fmq_ = std::move(q);
          }
        });
  }

  Return<void> constructDefaultRequestSettings(
      V3_2::RequestTemplate type,
      constructDefaultRequestSettings_cb _hidl_cb) override {
    return real_3_2_->constructDefaultRequestSettings(type, _hidl_cb);
  }

  Return<void> configureStreams(
      const V3_2::StreamConfiguration& requestedConfiguration,
      configureStreams_cb _hidl_cb) override {
    {
      std::lock_guard<std::mutex> lock(lock_);
      std::vector<StreamDesc> descs;
      descs.reserve(requestedConfiguration.streams.size());
      for (const auto& s : requestedConfiguration.streams) {
        descs.push_back({static_cast<int32_t>(s.format),
                         static_cast<uint64_t>(s.usage), s.width, s.height});
      }
      session_ = ClassifySession(camera_id_, descs, active_array_width_);
    }
    return real_3_2_->configureStreams(requestedConfiguration, _hidl_cb);
  }

  Return<void> configureStreams_3_3(
      const V3_2::StreamConfiguration& requestedConfiguration,
      configureStreams_3_3_cb _hidl_cb) override {
    if (real_3_3_ == nullptr) {
      _hidl_cb(Status::INTERNAL_ERROR, {});
      return Void();
    }
    {
      std::lock_guard<std::mutex> lock(lock_);
      std::vector<StreamDesc> descs;
      descs.reserve(requestedConfiguration.streams.size());
      for (const auto& s : requestedConfiguration.streams) {
        descs.push_back({static_cast<int32_t>(s.format),
                         static_cast<uint64_t>(s.usage), s.width, s.height});
      }
      session_ = ClassifySession(camera_id_, descs, active_array_width_);
    }
    return real_3_3_->configureStreams_3_3(requestedConfiguration, _hidl_cb);
  }

  Return<void> configureStreams_3_4(
      const V3_4::StreamConfiguration& requestedConfiguration,
      configureStreams_3_4_cb _hidl_cb) override {
    if (real_3_4_ == nullptr) {
      _hidl_cb(Status::INTERNAL_ERROR, {});
      return Void();
    }
    std::vector<uint8_t> rewritten_params;
    bool use_rewritten = false;
    {
      std::lock_guard<std::mutex> lock(lock_);
      std::vector<StreamDesc> descs;
      descs.reserve(requestedConfiguration.streams.size());
      for (const auto& s : requestedConfiguration.streams) {
        descs.push_back({static_cast<int32_t>(s.v3_2.format),
                         static_cast<uint64_t>(s.v3_2.usage), s.v3_2.width,
                         s.v3_2.height});
      }
      session_ = ClassifySession(camera_id_, descs, active_array_width_);
      if (!session_.has_raw16 && !session_.is_video &&
          session_.camera_id == 0) {
        use_rewritten = RewriteMetadataBytes(
            &session_, MetadataOrigin::kSessionParameters,
            requestedConfiguration.sessionParams.data(),
            requestedConfiguration.sessionParams.size(), tag_ids_,
            &rewritten_params);
      }
    }

    if (!use_rewritten) {
      return real_3_4_->configureStreams_3_4(requestedConfiguration, _hidl_cb);
    }

    V3_4::StreamConfiguration modified = requestedConfiguration;
    modified.sessionParams.setToExternal(rewritten_params.data(),
                                         rewritten_params.size());
    return real_3_4_->configureStreams_3_4(modified, _hidl_cb);
  }

  Return<void> processCaptureRequest(
      const hidl_vec<V3_2::CaptureRequest>& requests,
      const hidl_vec<V3_2::BufferCache>& cachesToRemove,
      processCaptureRequest_cb _hidl_cb) override {
    std::vector<std::vector<uint8_t>> backing_buffers;
    backing_buffers.reserve(requests.size() * 2);
    hidl_vec<V3_2::CaptureRequest> out_reqs(requests.size());

    {
      std::lock_guard<std::mutex> lock(lock_);
      for (size_t i = 0; i < requests.size(); ++i) {
        if (!PrepareV3_2RequestLocked(requests[i], &out_reqs[i],
                                      &backing_buffers)) {
          _hidl_cb(Status::INTERNAL_ERROR, 0);
          return Void();
        }
      }
    }
    return real_3_2_->processCaptureRequest(out_reqs, cachesToRemove, _hidl_cb);
  }

  Return<void> processCaptureRequest_3_4(
      const hidl_vec<V3_4::CaptureRequest>& requests,
      const hidl_vec<V3_2::BufferCache>& cachesToRemove,
      processCaptureRequest_3_4_cb _hidl_cb) override {
    if (real_3_4_ == nullptr) {
      _hidl_cb(Status::INTERNAL_ERROR, 0);
      return Void();
    }

    std::vector<std::vector<uint8_t>> backing_buffers;
    backing_buffers.reserve(requests.size() * 2);
    hidl_vec<V3_4::CaptureRequest> out_reqs(requests.size());

    {
      std::lock_guard<std::mutex> lock(lock_);
      for (size_t i = 0; i < requests.size(); ++i) {
        const V3_4::CaptureRequest& src = requests[i];
        V3_4::CaptureRequest& dst = out_reqs[i];

        // Per HIDL @3.4::PhysicalCameraSetting spec, v3_2 FMQ bytes precede
        // physicalCameraSettings[0..N-1] FMQ bytes in the queue.
        if (!PrepareV3_2RequestLocked(src.v3_2, &dst.v3_2, &backing_buffers)) {
          _hidl_cb(Status::INTERNAL_ERROR, 0);
          return Void();
        }

        dst.physicalCameraSettings.resize(src.physicalCameraSettings.size());
        for (size_t p = 0; p < src.physicalCameraSettings.size(); ++p) {
          const auto& src_phys = src.physicalCameraSettings[p];
          auto& dst_phys = dst.physicalCameraSettings[p];
          dst_phys.physicalCameraId = src_phys.physicalCameraId;

          const uint8_t* slice_ptr = nullptr;
          size_t slice_len = 0;
          if (src_phys.fmqSettingsSize > 0) {
            if (outer_req_fmq_ == nullptr) {
              ALOGE("Physical fmqSettingsSize > 0 without outer FMQ");
              _hidl_cb(Status::INTERNAL_ERROR, 0);
              return Void();
            }
            backing_buffers.emplace_back(src_phys.fmqSettingsSize);
            if (!outer_req_fmq_->read(backing_buffers.back().data(),
                                      src_phys.fmqSettingsSize)) {
              ALOGE("Failed reading physical settings from outer FMQ");
              _hidl_cb(Status::INTERNAL_ERROR, 0);
              return Void();
            }
            slice_ptr = backing_buffers.back().data();
            slice_len = backing_buffers.back().size();
          } else {
            slice_ptr = src_phys.settings.data();
            slice_len = src_phys.settings.size();
          }
          AttachSettingsToInnerFmqOrInline(slice_ptr, slice_len,
                                           &dst_phys.fmqSettingsSize,
                                           &dst_phys.settings);
        }
      }
    }

    return real_3_4_->processCaptureRequest_3_4(out_reqs, cachesToRemove,
                                                _hidl_cb);
  }

  Return<void> getCaptureRequestMetadataQueue(
      getCaptureRequestMetadataQueue_cb _hidl_cb) override {
    if (outer_req_fmq_ != nullptr && outer_req_fmq_->isValid()) {
      _hidl_cb(*outer_req_fmq_->getDesc());
      return Void();
    }
    return real_3_2_->getCaptureRequestMetadataQueue(_hidl_cb);
  }

  Return<void> getCaptureResultMetadataQueue(
      getCaptureResultMetadataQueue_cb _hidl_cb) override {
    return real_3_2_->getCaptureResultMetadataQueue(_hidl_cb);
  }

  Return<Status> flush() override { return real_3_2_->flush(); }

  Return<void> close() override { return real_3_2_->close(); }

 private:
  void AttachSettingsToInnerFmqOrInline(const uint8_t* ptr,
                                        size_t len,
                                        uint64_t* out_fmq_size,
                                        V3_2::CameraMetadata* out_inline) {
    if (len == 0 || ptr == nullptr) {
      *out_fmq_size = 0;
      out_inline->setToExternal(nullptr, 0);
      return;
    }
    if (inner_req_fmq_ != nullptr && inner_req_fmq_->isValid() &&
        inner_req_fmq_->write(ptr, len)) {
      *out_fmq_size = len;
      out_inline->setToExternal(nullptr, 0);
      return;
    }
    *out_fmq_size = 0;
    out_inline->setToExternal(const_cast<uint8_t*>(ptr), len);
  }

  bool PrepareV3_2RequestLocked(
      const V3_2::CaptureRequest& src,
      V3_2::CaptureRequest* dst,
      std::vector<std::vector<uint8_t>>* backing_buffers) {
    dst->frameNumber = src.frameNumber;
    CopyStreamBufferShallow(src.inputBuffer, &dst->inputBuffer);
    dst->outputBuffers.resize(src.outputBuffers.size());
    for (size_t b = 0; b < src.outputBuffers.size(); ++b) {
      CopyStreamBufferShallow(src.outputBuffers[b], &dst->outputBuffers[b]);
    }

    const uint8_t* slice_ptr = nullptr;
    size_t slice_len = 0;
    if (src.fmqSettingsSize > 0) {
      if (outer_req_fmq_ == nullptr) {
        ALOGE("fmqSettingsSize > 0 without valid outer FMQ");
        return false;
      }
      backing_buffers->emplace_back(src.fmqSettingsSize);
      if (!outer_req_fmq_->read(backing_buffers->back().data(),
                                src.fmqSettingsSize)) {
        ALOGE("Failed reading %llu bytes from outer RequestMetadataQueue",
              static_cast<unsigned long long>(src.fmqSettingsSize));
        return false;
      }
      slice_ptr = backing_buffers->back().data();
      slice_len = backing_buffers->back().size();
    } else {
      slice_ptr = src.settings.data();
      slice_len = src.settings.size();
    }

    if (slice_len > 0 && !session_.has_raw16 && !session_.is_video &&
        session_.camera_id == 0) {
      std::vector<uint8_t> rewritten;
      if (RewriteMetadataBytes(&session_, MetadataOrigin::kCaptureRequest,
                               slice_ptr, slice_len, tag_ids_, &rewritten)) {
        backing_buffers->push_back(std::move(rewritten));
        slice_ptr = backing_buffers->back().data();
        slice_len = backing_buffers->back().size();
      }
    }

    AttachSettingsToInnerFmqOrInline(slice_ptr, slice_len,
                                     &dst->fmqSettingsSize, &dst->settings);
    return true;
  }

  sp<V3_2::ICameraDeviceSession> real_3_2_;
  sp<V3_3::ICameraDeviceSession> real_3_3_;
  sp<V3_4::ICameraDeviceSession> real_3_4_;
  int camera_id_;
  float active_array_width_;
  VendorTagIds tag_ids_;
  std::unique_ptr<RequestMetadataQueue> outer_req_fmq_;
  std::unique_ptr<RequestMetadataQueue> inner_req_fmq_;
  std::mutex lock_;
  SessionPolicy session_;
};

class BlancCameraDevice final : public V3_2::ICameraDevice {
 public:
  BlancCameraDevice(sp<V3_2::ICameraDevice> real_device,
                    int camera_id,
                    const VendorTagIds& tag_ids)
      : real_device_(std::move(real_device)),
        camera_id_(camera_id),
        tag_ids_(tag_ids) {}

  Return<void> getResourceCost(getResourceCost_cb _hidl_cb) override {
    return real_device_->getResourceCost(_hidl_cb);
  }

  Return<void> getCameraCharacteristics(
      getCameraCharacteristics_cb _hidl_cb) override {
    return real_device_->getCameraCharacteristics(
        [this, &_hidl_cb](Status status,
                          const V3_2::CameraMetadata& characteristics) {
          if (status == Status::OK && characteristics.size() > 0) {
            CacheActiveArrayWidth(characteristics);
          }
          _hidl_cb(status, characteristics);
        });
  }

  Return<Status> setTorchMode(TorchMode mode) override {
    return real_device_->setTorchMode(mode);
  }

  Return<void> open(const sp<V3_2::ICameraDeviceCallback>& callback,
                    open_cb _hidl_cb) override {
    EnsureActiveArrayWidthCached();
    return real_device_->open(
        callback,
        [this, &_hidl_cb](Status status,
                          const sp<V3_2::ICameraDeviceSession>& real_sess) {
          if (status != Status::OK || real_sess == nullptr) {
            _hidl_cb(status, real_sess);
            return;
          }
          sp<V3_3::ICameraDeviceSession> sess_3_3;
          auto cast_3_3 = V3_3::ICameraDeviceSession::castFrom(real_sess);
          if (cast_3_3.isOk()) {
            sess_3_3 = cast_3_3;
          }
          sp<V3_4::ICameraDeviceSession> sess_3_4;
          auto cast_3_4 = V3_4::ICameraDeviceSession::castFrom(real_sess);
          if (cast_3_4.isOk()) {
            sess_3_4 = cast_3_4;
          }
          float active_w = 3968.0f;
          {
            std::lock_guard<std::mutex> lock(lock_);
            active_w = active_array_width_;
          }
          sp<V3_2::ICameraDeviceSession> wrapped = new BlancCameraDeviceSession(
              real_sess, sess_3_3, sess_3_4, camera_id_, active_w, tag_ids_);
          _hidl_cb(status, wrapped);
        });
  }

  Return<void> dumpState(const hidl_handle& fd) override {
    return real_device_->dumpState(fd);
  }

 private:
  void CacheActiveArrayWidth(const V3_2::CameraMetadata& characteristics) {
    std::lock_guard<std::mutex> lock(lock_);
    if (active_array_cached_) {
      return;
    }
    const size_t sz = characteristics.size();
    const auto* meta =
        reinterpret_cast<const camera_metadata_t*>(characteristics.data());
    if (validate_camera_metadata_structure(meta, &sz) ==
        CAMERA_METADATA_VALIDATION_ERROR) {
      return;
    }
    camera_metadata_ro_entry_t entry{};
    if (find_camera_metadata_ro_entry(
            meta, ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE, &entry) == 0 &&
        entry.count >= 4 && entry.data.i32[2] > 0) {
      active_array_width_ = static_cast<float>(entry.data.i32[2]);
      active_array_cached_ = true;
    }
  }

  void EnsureActiveArrayWidthCached() {
    {
      std::lock_guard<std::mutex> lock(lock_);
      if (active_array_cached_) {
        return;
      }
    }
    real_device_->getCameraCharacteristics(
        [this](Status status, const V3_2::CameraMetadata& characteristics) {
          if (status == Status::OK && characteristics.size() > 0) {
            CacheActiveArrayWidth(characteristics);
          }
        });
  }

  sp<V3_2::ICameraDevice> real_device_;
  int camera_id_;
  VendorTagIds tag_ids_;
  std::mutex lock_;
  float active_array_width_ = 3968.0f;
  bool active_array_cached_ = false;
};

class BlancCameraProvider final : public V2_5::ICameraProvider {
 public:
  BlancCameraProvider(sp<V2_4::ICameraProvider> real_2_4,
                      sp<V2_5::ICameraProvider> real_2_5)
      : real_2_4_(std::move(real_2_4)), real_2_5_(std::move(real_2_5)) {
    real_2_4_->getVendorTags(
        [this](Status status, const hidl_vec<VendorTagSection>& sections) {
          if (status == Status::OK) {
            std::lock_guard<std::mutex> lock(lock_);
            PopulateVendorTagsFromSections(sections, &tag_ids_);
            tags_resolved_ = true;
          }
        });
  }

  Return<Status> setCallback(
      const sp<V2_4::ICameraProviderCallback>& callback) override {
    return real_2_4_->setCallback(callback);
  }

  Return<void> getVendorTags(getVendorTags_cb _hidl_cb) override {
    return real_2_4_->getVendorTags(
        [this, &_hidl_cb](Status status,
                          const hidl_vec<VendorTagSection>& sections) {
          if (status == Status::OK) {
            std::lock_guard<std::mutex> lock(lock_);
            if (!tags_resolved_) {
              PopulateVendorTagsFromSections(sections, &tag_ids_);
              tags_resolved_ = true;
            }
          }
          _hidl_cb(status, sections);
        });
  }

  Return<void> getCameraIdList(getCameraIdList_cb _hidl_cb) override {
    return real_2_4_->getCameraIdList(_hidl_cb);
  }

  Return<void> isSetTorchModeSupported(
      isSetTorchModeSupported_cb _hidl_cb) override {
    return real_2_4_->isSetTorchModeSupported(_hidl_cb);
  }

  Return<void> getCameraDeviceInterface_V1_x(
      const hidl_string& cameraDeviceName,
      getCameraDeviceInterface_V1_x_cb _hidl_cb) override {
    return real_2_4_->getCameraDeviceInterface_V1_x(cameraDeviceName, _hidl_cb);
  }

  Return<void> getCameraDeviceInterface_V3_x(
      const hidl_string& cameraDeviceName,
      getCameraDeviceInterface_V3_x_cb _hidl_cb) override {
    const int cam_id = ParseCameraId(cameraDeviceName);
    VendorTagIds ids;
    {
      std::lock_guard<std::mutex> lock(lock_);
      ids = tag_ids_;
    }
    return real_2_4_->getCameraDeviceInterface_V3_x(
        cameraDeviceName,
        [cam_id, ids, &_hidl_cb](Status status,
                                 const sp<V3_2::ICameraDevice>& real_dev) {
          if (status != Status::OK || real_dev == nullptr) {
            _hidl_cb(status, real_dev);
            return;
          }
          sp<V3_2::ICameraDevice> wrapped =
              new BlancCameraDevice(real_dev, cam_id, ids);
          _hidl_cb(status, wrapped);
        });
  }

  Return<void> notifyDeviceStateChange(
      ::android::hardware::hidl_bitfield<V2_5::DeviceState> newState) override {
    if (real_2_5_ != nullptr) {
      return real_2_5_->notifyDeviceStateChange(newState);
    }
    return Void();
  }

 private:
  sp<V2_4::ICameraProvider> real_2_4_;
  sp<V2_5::ICameraProvider> real_2_5_;
  std::mutex lock_;
  VendorTagIds tag_ids_;
  bool tags_resolved_ = false;
};

struct BzServiceContext {
  void* server_trans = nullptr;
  void* bz_dlhandle = nullptr;
  uint32_t ok = 0;
};

std::mutex g_service_mutex;
std::shared_ptr<void> g_logger;
BzServiceContext* g_active_ctx = nullptr;
sp<V2_5::ICameraProvider> g_provider_proxy;

}  // namespace

extern "C" __attribute__((visibility("default"))) void setLogger(
    std::shared_ptr<void>* logger) {
  if (logger == nullptr) {
    return;
  }
  std::lock_guard<std::mutex> lock(g_service_mutex);
  if (!g_logger) {
    g_logger = *logger;
  }
}

extern "C" __attribute__((visibility("default"))) void initHalService(
    void** server_trans_uptr) {
  if (server_trans_uptr == nullptr || *server_trans_uptr == nullptr) {
    ALOGE("initHalService: server_trans_uptr is null");
    return;
  }

  auto* ctx = new BzServiceContext();
  ctx->server_trans = *server_trans_uptr;
  *server_trans_uptr = nullptr;

  constexpr const char* kBzPath = "/vendor/lib64/hw/hicam.hal.bz.so";
  ctx->bz_dlhandle = dlopen(kBzPath, RTLD_NOW);
  if (ctx->bz_dlhandle == nullptr) {
    ALOGE("initHalService: dlopen(%s) failed: %s", kBzPath, dlerror());
    delete ctx;
    return;
  }
  ctx->ok = 1;

  using CreateProviderFn = void (*)(void* ctx, void** out_runner);
  using SetLoggerFn = void (*)(std::shared_ptr<void>* logger);

  auto create_provider = reinterpret_cast<CreateProviderFn>(
      dlsym(ctx->bz_dlhandle, "createProvider"));
  if (create_provider == nullptr) {
    ALOGE("initHalService: dlsym(createProvider) failed: %s", dlerror());
    dlclose(ctx->bz_dlhandle);
    delete ctx;
    return;
  }

  void* runner_raw = nullptr;
  create_provider(ctx, &runner_raw);
  if (runner_raw == nullptr) {
    ALOGE("initHalService: createProvider returned null runner");
    dlclose(ctx->bz_dlhandle);
    delete ctx;
    return;
  }

  auto set_bz_logger =
      reinterpret_cast<SetLoggerFn>(dlsym(ctx->bz_dlhandle, "setLogger"));
  if (set_bz_logger != nullptr) {
    std::shared_ptr<void> local_logger;
    {
      std::lock_guard<std::mutex> lock(g_service_mutex);
      local_logger = g_logger;
    }
    set_bz_logger(&local_logger);
  }

  // Extract the unregistered V2_4::ICameraProvider* from runner + 8 before
  // invoking runner's deleting destructor (vtable[1]), then register our
  // V2_5::ICameraProvider interposer as "legacy/0".
  auto* raw_provider_2_4 = *reinterpret_cast<V2_4::ICameraProvider**>(
      reinterpret_cast<char*>(runner_raw) + sizeof(void*));
  if (raw_provider_2_4 == nullptr) {
    ALOGE("initHalService: runner provider pointer is null");
    auto** vtable = *reinterpret_cast<void***>(runner_raw);
    reinterpret_cast<void (*)(void*)>(vtable[1])(runner_raw);
    dlclose(ctx->bz_dlhandle);
    delete ctx;
    return;
  }

  sp<V2_4::ICameraProvider> real_2_4(raw_provider_2_4);
  {
    auto** vtable = *reinterpret_cast<void***>(runner_raw);
    reinterpret_cast<void (*)(void*)>(vtable[1])(runner_raw);
    runner_raw = nullptr;
  }

  sp<V2_5::ICameraProvider> real_2_5;
  auto cast_2_5 = V2_5::ICameraProvider::castFrom(real_2_4);
  if (cast_2_5.isOk()) {
    real_2_5 = cast_2_5;
  }

  sp<V2_5::ICameraProvider> proxy =
      new BlancCameraProvider(real_2_4, real_2_5);
  const ::android::status_t reg_status = proxy->registerAsService("legacy/0");
  if (reg_status != ::android::OK) {
    ALOGE("initHalService: registerAsService(legacy/0) failed: %d", reg_status);
    dlclose(ctx->bz_dlhandle);
    delete ctx;
    return;
  }

  {
    std::lock_guard<std::mutex> lock(g_service_mutex);
    g_provider_proxy = proxy;
    g_active_ctx = ctx;
  }
  ALOGI("BlancCameraProvider interposer registered as legacy/0 (@2.5=%d)",
        real_2_5 != nullptr ? 1 : 0);
}
