# VNDK29 libcrypto compatibility module (`libcrypto-v29`)

Provides the Android 10 / VNDK-29 BoringSSL `libcrypto.so` ABI under the
non-colliding `libcrypto-v29.so` SONAME for vendor executables compiled against
Android 10 BoringSSL internal struct layouts (specifically `/vendor/bin/iked`,
which directly reads/writes `struct dh_st` fields at offsets `0`/`8`/`16`/`24`/`32`
and allocates `104` bytes for `HMAC_CTX`).

LineageOS 24 BoringSSL (`external/boringssl`) added `BIGNUM *q;` at offset `16`
inside `struct dh_st`, shifting `pub_key` and `priv_key` and causing `iked` to
dereference a NULL field in `eay_dh_generate`.

## Provenance and exact transformation

- Input: public AOSP VNDK-29 snapshot at
  `2e659f04a7cde964415c82dd7507610769165408`, file
  `arm64/arch-arm64-armv8-a/shared/vndk-core/libcrypto.so`
  (`1145888` bytes, SHA-256
  `7161d6cf9e6feb6d2a5a8684c6bed9f7dd786490987fd00e240d269665e98215`).
- License notice: copied byte-for-byte from
  `common/NOTICE_FILES/libcrypto.so.txt` (`12517` bytes, SHA-256
  `60bd7c54856bf9387221bde5ab55d516d7cea15870d0fed69406bcd1c8ec7c9d`).
- Only transformation applied (`patchelf-0_9`):

```sh
patchelf-0_9 --set-soname libcrypto-v29.so arm64/libcrypto-v29.so
```

- Output: `arm64/libcrypto-v29.so` (`1190744` bytes, SHA-256
  `bfa372be0d226d400f84855e9058260acddf494acc28981b6b30a6f32f3f2433`),
  `DT_SONAME = libcrypto-v29.so`, `DT_NEEDED = [libc.so, libm.so, libdl.so]`,
  `PT_LOAD` alignments `[4096, 4096, 4096, 4096]`.

No proprietary vendor bytes are included.

## Consumer verification

- `/vendor/bin/iked`: all `75` BoringSSL imports resolve against `libcrypto-v29.so`.
- `/vendor/lib64/libracoon2.so`: `0` crypto/SSL dynamic symbol imports.

Run the audit locally from workspace root:

```sh
python3 -B cache/worktrees/packaging-hardware/compat/libcrypto-v29/audit.py
```
