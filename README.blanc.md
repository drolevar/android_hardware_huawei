# Huawei hardware support: Blanc / LineageOS 24 preparation

`blanc-lineage-24.0` preserves the hardware-support baseline
`f2b36a95e4284495ffda6b8b90721aa4e3d304aa` from
[LineageOS Gerrit 495271](https://review.lineageos.org/c/LineageOS/android_hardware_huawei/+/495271).
Use it with the
[pinned Huawei source manifest](https://github.com/drolevar/lineage_manifest/tree/blanc-lineage-24.0/huawei).
This branch reserves a reviewable home for platform adaptations; no runtime
compatibility or image-installation result is claimed.

## Image packaging to validate

The existing `mkbootimg.mk` is the selected implementation to inspect, not the
older Team Win `custombootimg.mk` recipe shown in third-party diagrams:

| Build output / target | Existing rule | Verification still needed |
| --- | --- | --- |
| `boot.img` / boot image target | Huawei kernel-tree mkbootimg wraps the kernel; the default split mode omits the normal ramdisk | Exact stock kernel header, offsets, AVB/footer behavior, physical destination and capacity |
| `boot_ramdisk.img` / `bootramdiskimage` | Separate normal ramdisk image with a null kernel | Actual normal ramdisk capacity and stock header contract |
| Recovery image target | Ramdisk image with a null kernel | Compatibility with the shared physical kernel and installed recovery boot path |
| `recovery_vendor.img` / `recoveryvendorimage` | Generates a placeholder vendor ramdisk | Whether the proposed replacement preserves all required recovery dependencies |

`releasetools/releasetools.py` also adds normal ramdisk and recovery vendor
images to OTA installation using by-name destinations. Those writes must be
reviewed against the BLA-L29 `.225` layout before producing an installable OTA.
No `/dev/block/sddN` mapping or combined "boot capacity" is inferred here.

The inherited VINTF matrices, HIDL interfaces, init code, health/IR services,
and SELinux policy need to be built and tested with Lineage 24 and the exact
BLA vendor stack. Source declarations do not establish working hardware or
LineageOS charter compliance. Existing authorship and licenses are retained.
