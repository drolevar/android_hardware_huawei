# BLA .225 TEE storage labels

The installed BLA-L29 EMUI12 .225 vendor file contexts distinguish:

- `/sec_storage`: `teecd_data_file` on the separate secure-storage filesystem.
- `/data/sec_storage_data*`: `teecd_data_file_system`, a core data type.
- `/data/vendor/sec_storage_data*`: a distinct vendor data type.

The donor conflated the first two. Android17 `sepolicy_test` rejects its root
`/data` labels because they lack `core_data_file_type`. Keep those legacy paths:
static IDA analysis of the captured stock `vendor/bin/teecd` (SHA-256
`0ab918ebde9fa420c66e60e17d78ed381801e9a6fd5cc67b1351ff5e963c6de2`)
shows an `access()` readiness check at function `0x6090` and an accepted-path
check at `0x7ef8` naming them. The original vendor CIL attributes
`teecd_data_file_system` as `data_file_type` and `core_data_file_type`.
The stock init file only creating vendor paths does not establish that legacy
paths are unused. Binary references and policy classification are static
observations, not proof of executed code or functional TEE/encryption.

The fix restores the separate core-data label; it does not move files, modify
binaries, add access permissions or change `/sec_storage` and vendor labels.
No credential contents or secure-storage partition bytes were inspected.
The existing service domains and permissions still require runtime integration.

Run the storage-label regression checks with:

```sh
python3 -B -m unittest discover -s sepolicy/hisi/tests -v
```

The current-platform `selinux_policy` target is the integration check; these
host tests do not establish hardware functionality.
