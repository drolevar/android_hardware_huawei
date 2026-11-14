# Bounded compatibility audits

The [libui-v29 audit](libui-v29-20261114.md) and its
[structured evidence](libui-v29-20261114.json) are **audit only**. No build module,
prebuilt library, shim, bypass, or proprietary BLA bytes are included.

Proven: both public maintainer binaries reproduce exactly from pinned AOSP
VNDK29 inputs; both hwcomposer ABIs retain their imports and all 17 UI imports
match the candidates. The unmodified candidate's direct `libhwbinder` dependency
is not visible to a module under `hardware/huawei`.

Reference failure: after experimentally removing the obsolete HIDL/binder
carriers, strict checking against the recorded reference set fails on four
`LIBSYNC`-versioned imports. This is **not a demonstrated LOS24 ABI failure**:
LOS24's source map declares those versions; final target providers were not
checked. The current undefined-module graph error remains unresolved.

Repeat the recorded audit from this hardware project:

```sh
python3 -B compat/audits/verify_libui_v29.py /absolute/path/to/huawei-blanc-los24
```

The verifier reads the selected source/public snapshots and private BLA references
in place, checks recorded identities, reconstructs only public candidates in a
temporary directory, and reproduces the expected strict-check failures. It does
not write a module, alter the evidence, build the source tree, or access a device.

Next concrete options are to stop the graph at this documented dependency, or
separately validate dependency normalization against actual LOS24 target/LLNDK
providers before proposing a module. Renaming VNDK32/34 libraries or disabling
checks is not supported by this audit.
