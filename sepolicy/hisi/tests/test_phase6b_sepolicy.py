# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
"""Verify Phase 6B .225-grounded SELinux file_contexts, mount types, and domain rules."""
import pathlib
import re
import unittest

POLICY = pathlib.Path(__file__).resolve().parents[1]
VENDOR = POLICY / "vendor"


def load_type_declarations():
    decls = {}
    for te_path in sorted(VENDOR.glob("*.te")):
        for m in re.finditer(r"(?m)^\s*type\s+(\w+)\s*(?:,\s*([^;]+))?;", te_path.read_text()):
            name = m.group(1)
            attrs = {a.strip() for a in (m.group(2) or "").split(",") if a.strip()}
            decls[name] = attrs
    return decls


def load_file_contexts():
    rules = []
    for raw in (VENDOR / "file_contexts").read_text().splitlines():
        line = raw.partition("#")[0].strip()
        if not line:
            continue
        pattern, context = line.split()
        rules.append((pattern, context.split(":")[2]))
    return rules


def resolve_type(rules, path):
    matches = [t for pat, t in rules if re.fullmatch(pat, path)]
    if len(matches) != 1:
        raise AssertionError(f"Expected 1 file_contexts match for {path}, got {matches}")
    return matches[0]


class Phase6BSepolicyTests(unittest.TestCase):
    def test_teecd_and_mount_labels(self):
        rules = load_file_contexts()
        self.assertEqual(resolve_type(rules, "/vendor/bin/teecd"), "tee_exec")
        self.assertEqual(resolve_type(rules, "/mnt/hisee_fs/cos.bin"), "hisee_data_file")
        self.assertEqual(resolve_type(rules, "/splash2/log/test.txt"), "splashtwo_file")
        self.assertEqual(
            resolve_type(rules, "/vendor/bin/hw/android.hardware.health-service.huawei"),
            "hal_health_default_exec",
        )
        self.assertEqual(
            resolve_type(rules, "/vendor/bin/hw/android.hardware.nfc-service.nxp"),
            "hal_nfc_default_exec",
        )
        self.assertEqual(
            resolve_type(rules, "/vendor/bin/hw/vendor.huawei.hardware.graphics.mediacomm@2.1-service"),
            "vendor_hal_hisi_media_default_exec",
        )
        self.assertEqual(
            resolve_type(rules, "/vendor/bin/start_connectivity_hi1103"),
            "vendor_hisi_connectivity_exec",
        )
        self.assertEqual(
            resolve_type(rules, "/odm/bin/glgps47531"),
            "vendor_hisi_connectivity_exec",
        )

    def test_dev_labels_use_dev_types_and_match_kirin970_nodes(self):
        decls = load_type_declarations()
        rules = load_file_contexts()

        # Public platform dev_types used directly in vendor file_contexts
        platform_dev_types = {
            "ion_device",
            "gpu_device",
            "sensors_device",
            "audio_device",
            "camera_device",
            "video_device",
            "hci_attach_dev",
            "radio_device",
            "nfc_device",
        }

        expected_dev = {
            "/dev/ion": "ion_device",
            "/dev/mali0": "gpu_device",
            "/dev/0:0:0:49476": "vendor_rpmb_device",
            "/dev/bsg/0:0:0:49476": "vendor_rpmb_device",
            "/dev/block/mmcblk0rpmb": "vendor_teecd_blkdev",
            "/dev/nve0": "vendor_nve_block_device",
            "/dev/oinv": "vendor_oeminfo_device",
            "/dev/oinv/oeminfo_test": "vendor_oeminfo_device",
            "/dev/hisi_teelog": "vendor_teelog_device",
            "/dev/hwlog_dubai": "vendor_hwlog_device",
            "/dev/sensorhub": "sensors_device",
            "/dev/hifi_misc": "audio_device",
            "/dev/hi_vdec": "vendor_vdec_device",
            "/dev/hi_venc": "vendor_venc_device",
            "/dev/ttyAMA4": "hci_attach_dev",
            "/dev/appvcom0": "radio_device",
            "/dev/haptics": "vendor_haptic_device",
            "/dev/pn544": "nfc_device",
        }
        for path, expected_type in expected_dev.items():
            with self.subTest(path=path):
                resolved = resolve_type(rules, path)
                self.assertEqual(resolved, expected_type)
                if resolved in decls:
                    self.assertIn("dev_type", decls[resolved])
                else:
                    self.assertIn(resolved, platform_dev_types)

        self.assertEqual(resolve_type(rules, "/dev/socket/oeminfo_nvm"), "vendor_oeminfo_nvm_socket")
        self.assertEqual(resolve_type(rules, "/dev/socket/hisecd"), "vendor_hisecd_socket")

    def test_contextmount_and_labeledfs_types_are_disjoint(self):
        decls = load_type_declarations()
        self.assertIn("fs_type", decls["hisee_data_file"])
        self.assertIn("contextmount_type", decls["hisee_data_file"])
        self.assertNotIn("file_type", decls["hisee_data_file"])

        self.assertIn("file_type", decls["modem_vendor_file"])
        self.assertIn("contextmount_type", decls["modem_vendor_file"])
        self.assertIn("vendor_file_type", decls["modem_vendor_file"])
        self.assertNotIn("fs_type", decls["modem_vendor_file"])
        self.assertIn("allow modem_vendor_file self:filesystem associate;", (VENDOR / "init.te").read_text())

        for fs_dir_type in ("teecd_data_file", "splashtwo_file", "modem_file"):
            self.assertIn("file_type", decls[fs_dir_type])
            self.assertNotIn("contextmount_type", decls[fs_dir_type])
            self.assertNotIn("fs_type", decls[fs_dir_type])

    def test_vendor_init_never_connects_to_vendor_sockets(self):
        oeminfo_te = re.sub(r"#.*", "", (VENDOR / "vendor_hisi_oeminfo_nvm_server.te").read_text())
        self.assertIn("type vendor_oeminfo_device, dev_type;", (VENDOR / "device.te").read_text())
        self.assertNotIn("vendor_init", oeminfo_te)
        tee_te = re.sub(r"#.*", "", (VENDOR / "tee.te").read_text())
        self.assertIn("data_between_core_and_vendor_violators", tee_te)
        self.assertNotIn("vendor_init", tee_te)

    def test_libteec_domain_and_xcollie_property_contexts(self):
        libteec_te = re.sub(r"#.*", "", (VENDOR / "vendor_hal_hisi_libteec_default.te").read_text())
        self.assertIn("type hal_libteec_default, domain;", libteec_te)
        self.assertIn("typealias hal_libteec_default alias vendor_hal_hisi_libteec_default;", libteec_te)
        self.assertIn("type hal_libteec_default_exec, exec_type, vendor_file_type, file_type;", libteec_te)
        self.assertIn("typealias hal_libteec_default_exec alias vendor_hal_hisi_libteec_default_exec;", libteec_te)
        self.assertIn("hal_client_domain(hal_drm_default, vendor_hal_hisi_libteec)", libteec_te)

        vfc = (VENDOR / "file_contexts").read_text()
        self.assertIn(
            "/vendor/bin/hw/vendor\\.huawei\\.hardware\\.libteec@[0-9]\\.0-service u:object_r:hal_libteec_default_exec:s0",
            vfc,
        )
        self.assertNotIn("u:object_r:vendor_hal_hisi_libteec_default_exec:s0", vfc)

        prop_te = (VENDOR / "property.te").read_text()
        self.assertIn("vendor_internal_prop(vendor_xcollie_prop)", prop_te)
        prop_ctx = (VENDOR / "property_contexts").read_text().splitlines()
        self.assertTrue(any(line.split() == ["odm.xcollie.", "u:object_r:vendor_xcollie_prop:s0"] for line in prop_ctx if line.strip()))
        clients_te = re.sub(r"#.*", "", (VENDOR / "hal_hisi_clients.te").read_text())
        self.assertIn("vendor_xcollie_prop", clients_te)
        self.assertIn("get_prop(hal_gatekeeper_default, logd_prop)", clients_te)

    def test_attempt5_nfc_service_context_oeminfo_and_usb_rules(self):
        svc_ctx = [
            line.split()
            for line in (VENDOR / "service_contexts").read_text().splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
        self.assertIn(
            ["vendor.nxp.nxpnfc_aidl.INxpNfc/default", "u:object_r:hal_nfc_service:s0"],
            svc_ctx,
        )

        init_te = re.sub(r"#.*", "", (VENDOR / "init.te").read_text())
        self.assertIn("allow init vendor_oeminfo_nvm_device:{ blk_file lnk_file } relabelto;", init_te)
        self.assertIn("allow ueventd vendor_oeminfo_nvm_device:lnk_file { read getattr };", init_te)

        vinit_te = re.sub(r"#.*", "", (VENDOR / "vendor_init.te").read_text())
        self.assertIn("allow vendor_init vendor_oeminfo_nvm_device:lnk_file { read getattr };", vinit_te)
        self.assertIn("allow vendor_init vendor_nve_block_device:chr_file getattr;", vinit_te)
        self.assertIn("r_dir_file(vendor_init, sysfs_android_usb)", vinit_te)
        self.assertIn("allow vendor_init sysfs_android_usb:file { w_file_perms setattr };", vinit_te)

    def test_attempt6_sphal_ion_vsync_mediacomm_camera_sensors_audio(self):
        rules = load_file_contexts()
        for so_path in (
            "/vendor/lib/libc_secshared.so",
            "/vendor/lib64/libc_secshared.so",
            "/vendor/lib64/hw/gralloc.kirin970.so",
            "/vendor/lib64/hw/android.hardware.graphics.mapper@2.0-impl-2.1.so",
        ):
            self.assertEqual(resolve_type(rules, so_path), "same_process_hal_file")
        self.assertEqual(
            resolve_type(rules, "/sys/devices/virtual/graphics/fb0/vsync_event"),
            "sysfs_framebuffer",
        )

        genfs_txt = (VENDOR / "genfs_contexts").read_text()
        self.assertIn("genfscon sysfs /devices/virtual/graphics/fb0 u:object_r:sysfs_framebuffer:s0", genfs_txt)
        self.assertIn("genfscon sysfs /firmware/devicetree/base/audio_hw_config u:object_r:sysfs_audio_hw_config:s0", genfs_txt)
        self.assertIn("genfscon sysfs /firmware/devicetree/base/smartpakit u:object_r:sysfs_audio_hw_config:s0", genfs_txt)

        media_te = re.sub(r"#.*", "", (VENDOR / "vendor_hal_hisi_media_default.te").read_text())
        self.assertIn("hal_server_domain(vendor_hal_hisi_media_default, vendor_hal_hisi_display)", media_te)

        clients_te = re.sub(r"#.*", "", (VENDOR / "hal_hisi_clients.te").read_text())
        for needle in (
            "ion_device:chr_file r_file_perms",
            "hal_client_domain(hal_camera_default, vendor_hal_hisi_display)",
            "hal_sensors_default sensors_device:chr_file rw_file_perms",
            "r_dir_file(hal_audio_default, sysfs_audio_hw_config)",
            "hal_audio_default self:netlink_kobject_uevent_socket create_socket_perms_no_ioctl",
        ):
            self.assertIn(needle, clients_te)


if __name__ == "__main__":
    unittest.main()
