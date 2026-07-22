# Explicit vendor JsonCpp VNDK30 compatibility library

The measured BLA `.225` `libhiview.so` imports the old `Json` C++ API. The
existing extraction fixup changes its dependency from `libjsoncpp.so` to
`libjsoncpp-v30.so`; its generated Blueprint already names that module and
imports the `hardware/huawei` namespace. LOS24 currently lacks the named module.
This directory supplies that explicit dependency for ARM and ARM64 without
replacing the platform JsonCpp library or altering the consumer/extraction rules.

## Public source and authorship

The two binaries are unchanged from Giovanni Ricca's public compatibility patch:

- Author: Giovanni Ricca <giovanniricca@proton.me>, 2026-07-22.
- [LineageOS Gerrit 494405](https://review.lineageos.org/c/LineageOS/android_hardware_lineage_compat/+/494405).
- Commit: `d5af95cca2b26b52a627ad806d6c45d2db5be71e`.
- Change-Id: `Ie6b16bd10c4d9e7051683c85e43614cd0d0819b7`.
- Input patch SHA-256:
  `2c8fec329680ee3815f0df71a25622f271656adc8752f48a53f89e5eb2c48b5b`.

Both were independently reproduced **byte for byte** from public
[`platform/prebuilts/vndk/v30`](https://android.googlesource.com/platform/prebuilts/vndk/v30/)
at `b2a975dfb122d37c37af780be63a9faf8d76d143`, available in the workspace's
pinned TWRP source tree. The snapshot file history identifies update commit
`0e2401cb12bef5c2d7598e5643248f767895a9fe`, build **7755159**, branch
`rvc-gsi-release`. Inputs are the two files under:

```text
arm64/arch-arm-armv8-a/shared/vndk-sp/libjsoncpp.so
arm64/arch-arm64-armv8-a/shared/vndk-sp/libjsoncpp.so
```

The ARM input is deliberately the ARMv8 32-bit variant from the ARM64 snapshot,
matching the maintainer's bytes; it is not the ARMv7 snapshot variant.
The only transformation is:

```sh
patchelf-0_9 --set-soname libjsoncpp-v30.so copied-libjsoncpp.so
```

The tool is from `prebuilts/extract-tools` commit
`aaeb969a477321411279c03799f45ae9f94f770b`. Exact tool/input/output hashes are
recorded in [AUDIT.json](AUDIT.json). No Huawei proprietary bytes are present
in this directory. Stock VNDK29 libraries are audit references only; these
libraries are public AOSP VNDK30, not renamed stock VNDK29.

`LICENSE` is the unmodified JsonCpp Public Domain/MIT notice from AOSP
`platform/external/jsoncpp` commit
`f59fb0e83fd0a4b41700d3f5eebdc8d21b173c2e` (the initial JsonCpp source import).
Its SHA-256 is
`76c45ece83a26117f86f4e349e7df118708e061e87225328fb478ce1e8b3eb86`.
The same notice is present in the historical pre-1.9 AOSP source, which declares
JsonCpp 1.0.0 in `README.version`; VNDK30 is an Android API/snapshot label, not
the JsonCpp software version. The module carries MIT/legacy-unencumbered license
metadata consistent with that source and retains the JsonCpp authors' notice.

## ELF and consumer audit (2026-11-14)

| Property | ARM | ARM64 |
| --- | --- | --- |
| Bytes | 109176 | 150352 |
| ELF | ELF32, little endian, ARM EABI5 soft-float ABI | ELF64, little endian, AArch64 |
| Android ident API | 30 | 30 |
| Build ID | `a47b0533d4bffd3c750c5e978cb8f158` | `3570ac3a8b05030b452866d11628d363` |
| Dynamic exports | 310 | 310 |
| Required `Json` imports covered | 21/21 | 21/21 |

Both have SONAME `libjsoncpp-v30.so`, four 4096-aligned load segments, and
`DT_NEEDED` entries `libc++.so`, `libc.so`, `libm.so`, `libdl.so`.
Imported versions are unversioned C++ symbols and `LIBC` versions from libc/libm;
the Json exports are unversioned. Both original and generated `libhiview` have
identical imported symbols; the generated `DT_NEEDED` list differs only by the
intended JsonCpp rename. Their complete 21-symbol Json requirements are in the
audit, including constructors/destructors, move assignment, readers, writers,
and `FastWriter`'s vtable. Both candidates export every required name/version.

The selected LOS24 `check_elf_file.py` passed for both ABIs with SONAME, all
`DT_NEEDED`, undefined-symbol resolution, and 4096-byte alignment checks enabled.
It was run separately against (1) stock libc++/bootstrap bionic references and
(2) the selected platform `clang-r596125` libc++ with the same stock bionic
references. Negative runs correctly rejected a wrong SONAME and undeclared
dependencies. REA's independent export inspection also found 310 exports and
no inventory issues per ABI. The selected platform's Blueprint `bpfmt -d`
parsed `Android.bp` successfully with no formatting changes through the checked-in
container runner, using image
`sha256:13c02403ad20d9ff81af455ed762d0c30eb1336d3990b16db3d33ebf44b0cbd8`.

The Blueprint declares all four dependencies explicitly, keeps the prebuilt
unstripped, and sets `check_elf_files: true`. It adds no undefined-symbol or
page-size exception. The observed product configuration selects 4096-byte pages;
the ARM64 prebuilt would fail a 16 KiB page requirement. The existing default
hardware namespace makes the module available to the consumer's existing import.

## Repeat the offline checks

From the workspace root, with this hardware worktree at the indicated path:

```sh
python3 -B cache/worktrees/hardware-225/compat/libjsoncpp-v30/audit.py \
  --platform src/lineage-24.0 \
  --vndk30 src/twrp-14.1/prebuilts/vndk/v30 \
  --firmware firmware/current/original-filesystems \
  --generated src/lineage-24.0/vendor/huawei/kirin970-common/proprietary/vendor \
  --output cache/worktrees/hardware-225/compat/libjsoncpp-v30/AUDIT.json
```

These checks do not build or execute the ARM binaries and do not access a device.
They establish public binary provenance and structural/symbol compatibility.
Actual Soong graph validation, final target dependency/link checks, C++ object
layout compatibility, linker namespace availability, and runtime behavior still
require separate evidence. In particular, reference-library checks are not a
claim that the final LOS24 bionic libraries have already been built or tested.
