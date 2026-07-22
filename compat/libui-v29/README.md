# VNDK29 libui adaptation for target validation

This provides the existing hwcomposer fixup's explicit `libui-v29` dependency.
It is a source-compatible adaptation attempt for actual Soong/target validation,
not a claim of runtime or C++ object-layout compatibility. No other target
provider, framework stack, consumer binary, or missing-dependency setting is changed.

## Provenance and the only transformations

Inputs are the public AOSP VNDK29 snapshot at
`2e659f04a7cde964415c82dd7507610769165408`, under
`arm64/arch-{arm,arm64}-armv8-a/shared/vndk-core/libui.so`.
The file update is `e04a093e13131184cfece3fe70a0624ec4178464`, build 7002685,
`qt-gsi-release`. The ARM input is the 32-bit ARMv8 variant.

First reproduce Giovanni Ricca's patch exactly: commit
`427c07be9e861910d05a9453f4a760dad128f136`, Gerrit 494395, Change-Id
`Ifa3746c46f275ec7d1f7074ea120a0872acf73e9`, authored 2026-07-22.
Then adapt only the obsolete dependencies:

```sh
patchelf-0_9 --set-soname libui-v29.so FILE
patchelf-0_9 --remove-needed libhidltransport.so FILE
patchelf-0_9 --remove-needed libhwbinder.so FILE
```

No symbols, versions, executable code, or data are patched. `prepare.py` guards
the exact input, SONAME-only maintainer output, reviewed platform sources, tool,
and notice hashes. It requires the final dependency list to equal the original
list minus exactly those two entries, with unchanged imports/exports. It also
compares all allocated sections except `.dynamic`/`.dynstr`; `.dynsym` section
indices are resolved to names because patchelf renumbers section headers.
Dynamic symbol addresses, sizes, bindings, visibility and resolved sections,
and allocated code/data contents and addresses must remain unchanged.

`NOTICE` is copied byte-for-byte from the snapshot's
`common/NOTICE_FILES/libui.so.txt`, including AOSP attribution and Apache 2.0:
`38751245389e1e23f73e6f5384b5cbe7fa972cc4410c5adc9c04b082a0b9561a`.
No proprietary BLA bytes are included. The original audit, input hashes,
authorship and reference identities remain in
[the dated audit](../audits/libui-v29-20261114.md) and its JSON evidence.

| ABI | Output bytes | Adapted output SHA-256 |
| --- | --- | --- |
| ARM | 163580 | `583e7a0c6356211ca02376dd869761c66056c7510dd3b5d78161567023403f4f` |
| ARM64 | 208392 | `24c42770590a84f906cadbcae7d46b106d54b6544c525cd2db204bd3aee79835` |

[TRANSFORM.json](TRANSFORM.json) records the guards, final dependencies and
preserved allocated-section hashes. `--write` creates only missing files after
both ABI transformations validate; it rejects differing existing output bytes.

## Proven source condition and retained dependencies

Selected `system/libhidl` revision `061055fc4dad78e5d25a77d9217001794656f16b`
defines `libhidltransport` as an empty carrier. `libhidlbase` uses
`libhidlbase-combined-impl`, which directly compiles the transport implementation
(including `HidlBinderSupport.cpp` and `ServiceManagement.cpp`). It also includes
`libhwbinder-impl-internal` through `whole_static_libs`.
Selected `system/libhwbinder` revision
`2f5282448223b97c541181a5b94a05c2ac17ff17` defines `libhwbinder` as an empty
carrier and compiles `IPCThreadState.cpp` into that internal implementation.
The exact reviewed Blueprint hashes are enforced before preparation/verification.

All 17 retained names were verified in the actual LOS24 source tree, including
allocator HIDL 2.0/3.0, graphics common 1.2, and mapper HIDL 2.0/2.1/3.0.
The others are `libbase`, `libcutils`, `libhardware`, `libhidlbase`, `libsync`,
`libutils`, `liblog`, `libc++`, `libc`, `libm`, `libdl`. All are explicitly
declared. No deprecated empty carrier is retained as a direct dependency.

## Verification and remaining target checks

Both committed binaries reproduce exactly and cover the original/generated
hwcomposer's complete 17-symbol stock-libui import set. Three guard/reproduction
tests pass. The selected platform Blueprint parser/formatter passed with no
formatting changes through the checked-in container runner. The strict reference
audit resolves every other mandatory import
(100 ARM / 99 ARM64 total imports) but rejects the same four versioned imports:
`sync_file_info@LIBSYNC`, `sync_file_info_free@LIBSYNC`, `sync_merge@LIBSYNC`,
`sync_wait@LIBSYNC`. The stock reference has unversioned exports. The selected
LOS24 source map declares `LIBSYNC`; that expected contract is not a completed
check of actual LOS24 target providers.

```sh
python3 -B compat/libui-v29/prepare.py /absolute/path/to/workspace
python3 -B compat/libui-v29/test_prepare.py /absolute/path/to/workspace
python3 -B compat/audits/verify_libui_v29.py /absolute/path/to/workspace --adapted
```

`prepare.py ... --write` reproduces missing artifacts from public inputs only.
The reference audit deliberately expects the four documented checker errors;
it never bypasses them or claims the target checks passed. The build module has
`check_elf_files: true`, no undefined-symbol or page-size exception, and preserves
4096-byte alignment. Main must publish/select the source and run actual graph,
target provider and ELF validation; those results must fail closed. Device
execution and hardware success remain untested.
