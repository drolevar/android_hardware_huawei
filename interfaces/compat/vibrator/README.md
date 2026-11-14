# Legacy AOSP vibrator HIDL definition

LineageOS 24 removed the AOSP vibrator 1.0 HIDL source module. Huawei's
`hwvibrator@1.0::IHWVibrator` extends its `IVibrator`; Huawei 1.1 extends that
Huawei 1.0 interface and imports the AOSP `Status` type. Their Soong generators
therefore require `android.hardware.vibrator@1.0_interface` even before compiling
any HAL implementation. Restore the source definition rather than removing
these interfaces or allowing missing dependencies.

## Provenance

`1.0/IVibrator.hal` and `1.0/types.hal` are byte-for-byte copies from
`hardware/interfaces/vibrator/1.0` at commit
`a155fffda456a650c3b89c5d13092172446782a8` in
`https://github.com/TeamWin/android_hardware_interfaces` (the checked-in TWRP
android-14.1 manifest pin). The 2016 AOSP copyright and Apache 2.0 headers are
unchanged. `NOTICE` is copied unchanged from that source repository; SHA-256:
`cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`.

The complete history in that checkout identifies these AOSP authors/commits:

| Commit | Author | Change |
| --- | --- | --- |
| `ca45b2dc29a02a74fa211327bdd39bb374946204` | Prashant Malani <pmalani@google.com> | Initial vibrator HIDL definition |
| `bf3bc86bea21d6d2ee504619d3e939b856d06850` | Michael Wright <michaelwr@google.com> | Amplitude control and predefined effects; last change to both copied HAL files |
| `e37df5463da9b4f5150263896d0bd7f1b839c64d` | Steven Moreland <smoreland@google.com> | Published `current.txt` hashes |

`current.txt` contains exactly the two vibrator 1.0 entries from lines 157-158
of the pinned repository's `current.txt`. These published HIDL hashes also
equal the raw file SHA-256 values:

```text
06ea64cc3565777f3b259e400ffa7100d07f3827ad9357b0c5d3c651384e5553 IVibrator.hal
0fecd34ae64f32eff6aa615fd662349242c0b8b6e303ef05a7cb5776c732f413 types.hal
```

No service, implementation, later interface version, or device configuration is
imported. The adapted `1.0/Android.bp` retains the upstream source/dependency
list, `gen_java: true`, and `gen_java_constants: true`.

## Package-root behavior

This package uses `hidl_package_root` named `android.hardware.vibrator`, with
its default path at this directory, and `root: "android.hardware.vibrator"` in
the restored module. `use_current` requires the local hash file;
`require_frozen` makes its generators pass `-F`. The existing global
`android.hardware` root and unrelated `hardware/interfaces` project are untouched.

Verified against `system/tools/hidl` commit
`5d0e05e6535fea94a674a359102bdd42944714f4`:

- `build/hidl_interface.go` accepts a root that is a package prefix, creates
  `_interface`, C++, Java, constants, lint and inheritance modules, and obtains
  generator root arguments from the participating interface dependencies.
  `android.hardware.vibrator@1.0` remains in its AOSP interface allowlist.
- `Coordinator::addPackagePath` rejects overlapping *explicit* roots. These
  Huawei interfaces need only `vendor.huawei`, `android.hardware.vibrator`, and
  `android.hidl`; the participating roots do not overlap.
- Normal generation and lint use `-R`. Inheritance generation omits `-R`;
  `addDefaultPackagePath` silently declines the overlapping default
  `android.hardware` mapping once the explicit vibrator mapping is present.
  Both Huawei inheritance chains were verified with this behavior.

The canonical names remain `android.hardware.vibrator@1.0` (C++),
`android.hardware.vibrator@1.0_interface`,
`android.hardware.vibrator-V1.0-java`, its `-java-shallow` counterpart, and
`android.hardware.vibrator-V1.0-java-constants`. The HIDL package descriptors,
generated include/Java package paths, and API hashes are unchanged by the root
choice. These modules inherit the existing `hardware/huawei` Soong namespace,
as do their Huawei consumers; no additional namespace or root alias is added.

A future interface that imports both this package and another package under
the broad `android.hardware` root cannot combine both explicit roots in one
generator invocation. That would require a reviewed unified source-root layout.
If upstream restores vibrator 1.0, remove this compatibility copy deliberately
to avoid a competing canonical definition.

## Verification

From this hardware project, check source integrity:

```sh
python3 -B -m unittest discover -s interfaces/compat/vibrator/tests -v
```

To run the six generator tests as well, set `HIDL_GEN` to an executable from the
selected platform and `ANDROID_BUILD_TOP` to that platform's source tree:

```sh
HIDL_GEN=/absolute/path/to/hidl-gen ANDROID_BUILD_TOP=/absolute/path/to/platform \
  python3 -B -m unittest discover -s interfaces/compat/vibrator/tests -v
```

On 2026-11-14, all seven tests passed with no skips using a standalone build of
the selected LOS24 generator inside the workspace's checked-in container runner.
The fixture copied only this package and the existing Huawei vibrator sources
into container temporary storage. It checked frozen hashes, C++ headers/sources,
Java and exported constants, both Huawei versions and inheritance chains, and
negative cases for a missing base, corrupted hash, and conflicting explicit root.
The selected platform's Blueprint `bpfmt -d` also parsed both added build files
successfully with no formatting changes.

The generator was built from unmodified platform source with the source Clang
`clang-r596125`, source Bison/Flex, container libstdc++/libcrypto, and explicit
`cstdint`/`cstring` includes for that standalone host-library combination.
It is a generator check, not a Soong or Android library build.

```text
container image: sha256:13c02403ad20d9ff81af455ed762d0c30eb1336d3990b16db3d33ebf44b0cbd8
temporary hidl-gen SHA-256: 41ab42682ce274ea59e9dccb796e9b83ef440bd6a41f75fdd5038a8fa011744c
Blueprint source: 7f2058de8155b05ea3c3f8317d33bfe732cacf8b
android.hidl source: 061055fc4dad78e5d25a77d9217001794656f16b
Clang prebuilt project: 29182889399818f97baa65ea238c80e8117981eb
```

Full Soong graph evaluation and C++/Java library compilation remain for the main
build after publication and source-pin selection. This source restoration does
not establish stock ABI matching, service availability, VINTF compatibility,
AIDL integration, boot success, or working vibration on a device.
