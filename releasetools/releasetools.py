#!/bin/env python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

import common

_BLANC_DEVICE = "blanc"
# Physical cache (sdd65) backs /metadata and /cache is /data/cache, so
# BOARD_CACHEIMAGE_PARTITION_SIZE is omitted; provide a 128 MiB fallback for
# AOSP non_ab_ota / blockimgdiff when OPTIONS.cache_size is None.
_BLANC_FALLBACK_OTA_CACHE_SIZE = 134217728

_BLANC_EMUI12_225_ASSERTIONS = (
    (
        'is_substring("fastboot_version=Chipset-boston102.0.0.", '
        'read_file("/proc/cmdline"))',
        "E3001: EMUI 12 .225 bootloader (Chipset-boston102.0.0.*) is required before flashing blanc LineageOS 24.",
    ),
    (
        'is_substring("devmodel=BLA-L29", read_file("/proc/cmdline")) || '
        'is_substring("devmodel=BLA-L09", read_file("/proc/cmdline"))',
        "E3002: Unsupported Huawei device model in /proc/cmdline (expected BLA-L29 or BLA-L09).",
    ),
    (
        'is_substring("PARTNAME=super\\n", read_file("/sys/block/sdd/sdd67/uevent")) && '
        'read_file("/sys/block/sdd/sdd67/size") == "11272192\\n"',
        "E3003: Super partition layout mismatch on /sys/block/sdd/sdd67 (EMUI 12 .225 5.77 GiB super required).",
    ),
    (
        'is_substring("PARTNAME=kernel\\n", read_file("/sys/block/sdd/sdd38/uevent")) && '
        'read_file("/sys/block/sdd/sdd38/size") == "49152\\n"',
        "E3004: Kernel partition layout mismatch on /sys/block/sdd/sdd38 (expected EMUI 12 .225 24 MiB kernel).",
    ),
    (
        'is_substring("PARTNAME=ramdisk\\n", read_file("/sys/block/sdd/sdd53/uevent")) && '
        'read_file("/sys/block/sdd/sdd53/size") == "4096\\n"',
        "E3005: Ramdisk partition layout mismatch on /sys/block/sdd/sdd53 (expected EMUI 12 .225 2 MiB ramdisk).",
    ),
)


def _GetInputZip(info):
  target_zip = getattr(info, "target_zip", None)
  return target_zip if target_zip is not None else info.input_zip


def _ReadPropFromZip(zip_file, prop_keys):
  for prop_path in ("SYSTEM/build.prop", "VENDOR/build.prop"):
    try:
      content = zip_file.read(prop_path).decode("utf-8", errors="ignore")
    except KeyError:
      continue
    props = {}
    for raw_line in content.splitlines():
      line = raw_line.strip()
      if not line or line.startswith("#") or "=" not in line:
        continue
      k, v = line.split("=", 1)
      props[k.strip()] = v.strip()
    for key in prop_keys:
      val = props.get(key)
      if val:
        return val.lower()
  return None


def _GetDeviceFromInfoDict(info_dict):
  if info_dict is None:
    return None
  prop_keys = (
      "ro.product.device",
      "ro.product.system.device",
      "ro.product.vendor.device",
      "ro.lineage.device",
  )
  for dict_key in ("build.prop", "system.build.prop", "vendor.build.prop"):
    prop_obj = info_dict.get(dict_key)
    if prop_obj is None:
      continue
    get_prop = getattr(prop_obj, "GetProp", None)
    if get_prop is None:
      continue
    for key in prop_keys:
      val = get_prop(key)
      if val:
        return str(val).strip().lower()
  return None


def _GetTargetDevice(info):
  metadata = getattr(info, "metadata", None)
  postcondition = getattr(metadata, "postcondition", None)
  devices = getattr(postcondition, "device", None)
  if devices:
    for dev in devices:
      if dev:
        return str(dev).strip().lower()

  dev = _GetDeviceFromInfoDict(getattr(info, "info_dict", None))
  if dev:
    return dev

  input_zip = _GetInputZip(info)
  if input_zip is not None:
    return _ReadPropFromZip(
        input_zip,
        (
            "ro.product.system.device",
            "ro.product.vendor.device",
            "ro.product.device",
            "ro.lineage.device",
        ),
    )
  return None


def _EnsureBlancCacheSize(info=None):
  options = getattr(common, "OPTIONS", None)
  if options is None or getattr(options, "cache_size", None) is not None:
    return
  if info is not None:
    dev = _GetTargetDevice(info)
  else:
    dev = _GetDeviceFromInfoDict(getattr(options, "target_info_dict", None))
    if not dev:
      dev = _GetDeviceFromInfoDict(getattr(options, "info_dict", None))
  if dev == _BLANC_DEVICE:
    options.cache_size = _BLANC_FALLBACK_OTA_CACHE_SIZE


def FullOTA_Assertions(info):
  OTA_Assertions(info)
  return


def IncrementalOTA_Assertions(info):
  OTA_Assertions(info)
  return


def OTA_Assertions(info):
  _EnsureBlancCacheSize(info)
  if _GetTargetDevice(info) != _BLANC_DEVICE:
    return
  info.script.Comment("EMUI 12 .225 bootloader and Kirin 970 partition geometry assertions for blanc")
  for expr, msg in _BLANC_EMUI12_225_ASSERTIONS:
    info.script.AppendExtra('assert((%s) || abort("%s"));' % (expr, msg))
  return


def FullOTA_InstallEnd(info):
  OTA_InstallEnd(info)
  return


def IncrementalOTA_InstallEnd(info):
  OTA_InstallEnd(info)
  return


def AddImage(info, dir, basename, dest):
  data = _GetInputZip(info).read(dir + "/" + basename)
  common.ZipWriteStr(info.output_zip, basename, data)
  info.script.Print("Patching {} image unconditionally...".format(dest.split('/')[-1]))
  info.script.AppendExtra('package_extract_file("%s", "%s");' % (basename, dest))


def ImageExists(info, dir, basename):
  try:
    _GetInputZip(info).getinfo(dir + "/" + basename)
    return True
  except KeyError:
    return False


def OTA_InstallEnd(info):
  if _GetTargetDevice(info) == _BLANC_DEVICE:
    if ImageExists(info, "RADIO", "recovery_vendor.img"):
      raise RuntimeError(
          "Unexpected RADIO/recovery_vendor.img in blanc target-files; "
          "EMUI 12 .225 must never overwrite recovery_vendor"
      )
    if not ImageExists(info, "RADIO", "boot_ramdisk.img"):
      raise RuntimeError("Missing required RADIO/boot_ramdisk.img for blanc OTA")
    AddImage(info, "RADIO", "boot_ramdisk.img", "/dev/block/by-name/ramdisk")
    return

  if ImageExists(info, "RADIO", "boot_ramdisk.img"):
    AddImage(info, "RADIO", "boot_ramdisk.img", "/dev/block/by-name/ramdisk")
  if ImageExists(info, "RADIO", "recovery_vendor.img"):
    AddImage(info, "RADIO", "recovery_vendor.img", "/dev/block/by-name/recovery_vendor")
  return


_EnsureBlancCacheSize()
