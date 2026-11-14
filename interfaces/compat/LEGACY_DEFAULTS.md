# Legacy memtrack and vibrator 1.0 default services

Restores the four standard modules already requested by the product:
`android.hardware.memtrack@1.0-impl`, `android.hardware.memtrack@1.0-service`,
`android.hardware.vibrator@1.0-impl`, `android.hardware.vibrator@1.0-service`.
The current platform also lacks memtrack's frozen 1.0 HIDL definition, so it is
restored here. The existing compatibility vibrator IDL is reused.

## Provenance and source changes

All C++ sources, headers and init rc files, plus `IMemtrack.hal` and `types.hal`,
are unchanged copies from the selected TWRP `hardware/interfaces` project at
`a155fffda456a650c3b89c5d13092172446782a8` (TeamWin/android_hardware_interfaces).
Original AOSP copyright and Apache 2.0 headers are retained. Exact file hashes,
last upstream commits/authors, initial implementation/service commits and
sanitized stock evidence are in [legacy-defaults-provenance.json](legacy-defaults-provenance.json).
The original source history remains available through that pinned repository.

Build-only adaptations replace the original project-local license reference with
the scoped compatibility license. Memtrack's old `proprietary: true` partition
alias becomes `vendor: true`; all four modules remain vendor modules with their
original install locations, dependencies and rc files. No adapter behavior is
changed. The selected upstream definitions already depend on merged `libhidlbase`
without obsolete libhidltransport/libhwbinder dependencies. No link, dependency,
ELF, or VINTF check suppression is introduced.

Memtrack uses the dedicated `android.hardware.memtrack` package root, local
`current.txt`, and required frozen hashes, following the existing vibrator
restoration. It does not redefine the global `android.hardware` root. The old
IDL Blueprint's obsolete `vndk` property is omitted because the current HIDL
module type no longer supports it; sources, dependencies and Java generation
are retained. Published API/raw source SHA-256 values are:

```text
c66902fe48d687ac6740a3e32ae55fb75532c48c36c6386461c2b4416ad2e0f1 IMemtrack.hal
860bacd8b11a269c40567542b613fe4ca448d5cb4326d0058899e608e89dfca1 types.hal
```

The new memtrack NOTICE is copied unchanged from the pinned AOSP source; vibrator
inherits its existing compatibility notice/license. No proprietary bytes are added.

## Evidence for the legacy C adapters

The measured BLA vendor manifest declares both standard 1.0 interfaces with
hwbinder/default instances. Both ABI stock `memtrack.default.so` and
`vibrator.default.so` export the legacy `HMI` C module object. The stock vibrator
identifies the default timed-output implementation; the current platform retains
that `vibrator.default` C module, selected by the inherited `handheld_vendor.mk`
product base. Existing extraction supplies both `memtrack.kirin970.so` variants
from the measured `memtrack.default.so` files. These observations support restoring
the standard `hw_get_module`/legacy-C-module wrappers as a build baseline.

This does not prove successful module loading or hardware operation. The stock
manifest also advertises Huawei's extended hwvibrator 1.1 service; this change
restores only the four requested standard modules, not that proprietary service.

## Verification

```sh
python3 -B interfaces/compat/verify_legacy_defaults.py
HIDL_GEN=/absolute/path/to/current/hidl-gen \
ANDROID_BUILD_TOP=/absolute/path/to/lineage-24.0 \
  python3 -B interfaces/compat/verify_legacy_defaults.py
```

Verified on 2026-11-14 through the checked-in container runner:

- Ten imported source/header/rc/IDL files match their upstream hashes exactly.
- A temporary generator built from the selected LOS24 `system/tools/hidl`
  (`5d0e05e6535fea94a674a359102bdd42944714f4`) generated frozen base/manager,
  memtrack and vibrator C++ headers successfully with `-R -F`.
- Both implementation and both service translation units passed host C++ syntax
  checks with current source headers, source Clang `clang-r596125`, its libc++,
  `-Wall -Werror`, and no adapter source edits.
- All four added Blueprints passed the selected platform parser/formatter.

Container image:
`sha256:13c02403ad20d9ff81af455ed762d0c30eb1336d3990b16db3d33ebf44b0cbd8`.
The standalone generator uses container host libstdc++/libcrypto; those libraries
are verification tools, not target dependencies. These checks are not a completed
Android target compilation, link, registration, VINTF, service execution or device
test. Actual target build results remain with the main build. Configstore is
outside this change.
