# Explicit vendor TinyXML2 VNDK29 compatibility library

The measured BLA `.225` proprietary camera libraries (`hwcam.kirin970.*.so`,
`hicam.hal.bz.so`, `libcamera_perftrace.so`) import `tinyxml2::XMLDocument`
and sibling classes compiled against Android 10 VNDK29 `libtinyxml2.so`
(where `tinyxml2::XMLDocument` is smaller than Android 17's `vendor/lib64/libtinyxml2.so`,
causing stack corruption past the caller-allocated `XMLDocument` during
`CameraDaemon` `hispmgr` initialization).

This directory provides `libtinyxml2-v29` for ARM (32-bit ARMv8) and ARM64
directly from public AOSP VNDK29 without replacing the platform `libtinyxml2.so`.
No Huawei proprietary bytes are present in this directory.

## Public source and transformation

Inputs are the public [`platform/prebuilts/vndk/v29`](https://android.googlesource.com/platform/prebuilts/vndk/v29/)
files at revision `2e659f04a7cde964415c82dd7507610769165408`:

```text
arm64/arch-arm-armv8-a/shared/vndk-core/libtinyxml2.so
arm64/arch-arm64-armv8-a/shared/vndk-core/libtinyxml2.so
```

Only the DT_SONAME is updated:

```sh
patchelf-0_9 --set-soname libtinyxml2-v29.so libtinyxml2-v29.so
```

`LICENSE` is copied byte-for-byte from
`common/NOTICE_FILES/libtinyxml2.so.txt` in that snapshot (zlib license).
Exact input/output hashes, `check_elf_file.py` runs (positive and negative),
and symbol coverage (`5/5` ARM and `22/22` ARM64 consumer `tinyxml2` imports)
are recorded in [AUDIT.json](AUDIT.json) by [audit.py](audit.py).

## Repeat the offline audit

```sh
python3 -B compat/libtinyxml2-v29/audit.py \
  --platform ../../src/lineage-24.0 \
  --vndk29 ../../src/twrp-14.1/prebuilts/vndk/v29 \
  --firmware ../../firmware/current/original-filesystems \
  --output compat/libtinyxml2-v29/AUDIT.json
```
