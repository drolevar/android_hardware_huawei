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
        # libiawareperf_client.so must not override AOSP's default vendor_file label in hw file_contexts.
        self.assertEqual([t for pat, t in rules if re.fullmatch(pat, "/vendor/lib64/libiawareperf_client.so")], [])
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

    def test_attempt7_ddrfreq_nve_mac_bluetooth_and_fingerprint(self):
        decls = load_type_declarations()
        self.assertIn("data_file_type", decls["vendor_bluedroid_data_file"])
        self.assertIn("data_file_type", decls["vendor_wlan_data_file"])

        rules = load_file_contexts()
        self.assertEqual(resolve_type(rules, "/data/vendor/bluedroid/macbt"), "vendor_bluedroid_data_file")
        self.assertEqual(resolve_type(rules, "/data/vendor/wifi/macwlan"), "vendor_wlan_data_file")

        genfs_txt = (VENDOR / "genfs_contexts").read_text()
        self.assertIn("genfscon sysfs /devices/platform/fffc0000.ddr_devfreq/devfreq/ddrfreq u:object_r:sysfs_devfreq_dir:s0", genfs_txt)
        self.assertIn("genfscon sysfs /class/devfreq u:object_r:sysfs_devfreq_dir:s0", genfs_txt)
        self.assertIn("genfscon sysfs /devices/platform/bluetooth_power/rfkill/rfkill0 u:object_r:sysfs_bluetooth_writable:s0", genfs_txt)
        self.assertIn("genfscon proc /bluetooth/sleep u:object_r:proc_bluetooth_writable:s0", genfs_txt)

        prop_te = (VENDOR / "property.te").read_text()
        self.assertIn("vendor_internal_prop(vendor_hisi_conn_prop)", prop_te)
        prop_ctx = (VENDOR / "property_contexts").read_text().splitlines()
        self.assertTrue(
            any(line.split() == ["vendor.conn_macs.ready", "u:object_r:vendor_hisi_conn_prop:s0"] for line in prop_ctx if line.strip())
        )

        hisi_init_te = re.sub(r"#.*", "", (VENDOR / "vendor_hisi_init.te").read_text())
        for needle in (
            "allow vendor_hisi_init block_device:dir search;",
            "allow vendor_hisi_init vendor_nve_block_device:blk_file r_file_perms;",
            "allow vendor_hisi_init { vendor_bluedroid_data_file vendor_wlan_data_file }:file create_file_perms;",
            "set_prop(vendor_hisi_init, vendor_hisi_conn_prop)",
            "get_prop(vendor_init, vendor_hisi_conn_prop)",
        ):
            self.assertIn(needle, hisi_init_te)

        oeminfo_te = re.sub(r"#.*", "", (VENDOR / "vendor_hisi_oeminfo_nvm_server.te").read_text())
        self.assertIn("allow vendor_hisi_oeminfo_nvm_server vendor_nve_block_device:chr_file rw_file_perms;", oeminfo_te)

        clients_te = re.sub(r"#.*", "", (VENDOR / "hal_hisi_clients.te").read_text())
        for needle in (
            "hal_client_domain(hal_graphics_composer_default, hal_graphics_allocator)",
            "r_dir_file({ hal_graphics_composer_default vendor_hal_hisi_display_default }, sysfs_devfreq_dir)",
            "allow hal_graphics_composer_default self:netlink_kobject_uevent_socket create_socket_perms_no_ioctl;",
            "allow mediacodec { vendor_vdec_device vendor_venc_device vendor_jpu_device }:chr_file rw_file_perms;",
            "r_dir_file(hal_bluetooth_default, vendor_bluedroid_data_file)",
            "r_dir_file(hal_wifi_default, vendor_wlan_data_file)",
            "allow hal_fingerprint_default vendor_fingerprint_device:chr_file rw_file_perms;",
        ):
            self.assertIn(needle, clients_te)

    def test_attempt8_leds_sensors_camera_ril_bt_vibrator_gps_power_drm(self):
        decls = load_type_declarations()
        for data_type in ("vendor_camera_data_file", "vendor_gps_data_file"):
            self.assertIn("data_file_type", decls[data_type])
        for fs_type in ("sysfs_huawei_sensor", "sysfs_camerafs", "proc_gps", "proc_gpu_memory", "proc_wakeup_sources"):
            self.assertIn("fs_type", decls[fs_type])

        rules = load_file_contexts()
        self.assertEqual(
            resolve_type(rules, "/vendor/bin/hw/vendor.huawei.hardware.camera.factory@1.1-service"),
            "hal_camera_default_exec",
        )
        self.assertEqual(
            resolve_type(rules, "/vendor/bin/hw/android.hardware.vibrator-service.huawei"),
            "hal_vibrator_default_exec",
        )
        self.assertEqual(resolve_type(rules, "/dev/ttyAMA3"), "vendor_gps_device")
        self.assertEqual(resolve_type(rules, "/dev/gpstty"), "vendor_gps_device")
        self.assertEqual(resolve_type(rules, "/data/vendor/camera/img/test.jpg"), "vendor_camera_data_file")
        self.assertEqual(resolve_type(rules, "/data/vendor/gps/gpsconfig.xml"), "vendor_gps_data_file")
        self.assertEqual(resolve_type(rules, "/data/vendor/gnss/lhd.conf"), "vendor_gps_data_file")

        genfs_txt = (VENDOR / "genfs_contexts").read_text()
        for needle in (
            "genfscon sysfs /devices/platform/hisi_fb.1048577/leds u:object_r:sysfs_leds:s0",
            "genfscon sysfs /devices/platform/hisilicon-hisi-led-pmic-spmi-pmic_led/leds u:object_r:sysfs_leds:s0",
            "genfscon sysfs /devices/platform/amba/fdf0d000.i2c/i2c-4/4-005a/leds u:object_r:sysfs_leds:s0",
            "genfscon sysfs /devices/platform/huawei_sensor u:object_r:sysfs_huawei_sensor:s0",
            "genfscon sysfs /devices/virtual/sensors u:object_r:sysfs_huawei_sensor:s0",
            "genfscon sysfs /class/sensors u:object_r:sysfs_huawei_sensor:s0",
            "genfscon sysfs /devices/virtual/camerafs u:object_r:sysfs_camerafs:s0",
            "genfscon sysfs /class/camerafs u:object_r:sysfs_camerafs:s0",
            "genfscon proc /bluetooth/calibrate u:object_r:proc_bluetooth_writable:s0",
            "genfscon proc /gps u:object_r:proc_gps:s0",
            "genfscon proc /wakeup_sources u:object_r:proc_wakeup_sources:s0",
            "genfscon proc /gpu_memory u:object_r:proc_gpu_memory:s0",
            "genfscon proc /memtrack u:object_r:proc_gpu_memory:s0",
            "genfscon sysfs /devices/platform/battery/power_supply u:object_r:sysfs_batteryinfo:s0",
        ):
            self.assertIn(needle, genfs_txt)

        hw_ctx = (POLICY / "private/hwservice_contexts").read_text()
        for iface in (
            "vendor.huawei.hardware.camera.camResource::IHwCameraResourceService",
            "vendor.huawei.hardware.camera.factory::ICameraFactory",
            "vendor.huawei.hardware.camera.vircamera::IVirCameraChannel",
            "vendor.huawei.hardware.hisiradio::IHisiRadio",
            "vendor.huawei.hardware.radio.chr::IRadioChr",
            "vendor.huawei.hardware.radio.deprecated::IOemHook",
            "vendor.huawei.hardware.radio.ims::IRadioIms",
        ):
            self.assertIn(iface, hw_ctx)

        prop_te = (VENDOR / "property.te").read_text()
        for prop_type in (
            "vendor_power_prop",
            "vendor_camera_prop",
            "vendor_gps_prop",
            "vendor_ctl_gpsd_prop",
            "vendor_rild_prop",
        ):
            self.assertIn(f"vendor_internal_prop({prop_type})", prop_te)

        clients_te = re.sub(r"#.*", "", (VENDOR / "hal_hisi_clients.te").read_text())
        for needle in (
            "r_dir_file(hal_sensors_default, sysfs_huawei_sensor)",
            "hal_client_domain(hal_camera_default, hal_sensors)",
            "hal_client_domain(hal_camera_default, hal_graphics_allocator)",
            "hal_client_domain(hal_camera_default, hal_memtrack)",
            "allow hal_bluetooth_default proc_bluetooth_writable:dir r_dir_perms;",
            "set_prop(hal_power_default, vendor_power_prop)",
            "allow hal_memtrack_default proc_gpu_memory:file r_file_perms;",
            "r_dir_file(hal_audio_default, sysfs_batteryinfo)",
            "r_dir_file(hal_audio_default, sysfs_huawei_sensor)",
            "allow hal_audio_default sysfs_huawei_sensor:file rw_file_perms;",
        ):
            self.assertIn(needle, clients_te)

        priv_prop_ctx = (POLICY / "private/property_contexts").read_text().splitlines()
        for prop_key in (
            "ro.connectivity.chiptype",
            "ro.connectivity.sub_chiptype",
            "ro.quick_broadcast_cardstatus",
        ):
            self.assertTrue(
                any(
                    line.split()[:2] == [prop_key, "u:object_r:exported_default_prop:s0"]
                    for line in priv_prop_ctx
                    if line.strip() and not line.lstrip().startswith("#")
                ),
                f"Missing {prop_key} in private/property_contexts",
            )
        for prop_key in ("ro.config.hisi_cdma_supported", "ro.config.dsds_mode"):
            self.assertTrue(
                any(
                    line.split()[:2] == [prop_key, "u:object_r:telephony_config_prop:s0"]
                    for line in priv_prop_ctx
                    if line.strip() and not line.lstrip().startswith("#")
                ),
                f"Missing {prop_key} in private/property_contexts",
            )

        vnd_prop_ctx = (VENDOR / "property_contexts").read_text().splitlines()
        for prop_key in (
            "vendor.rilprops_ready",
            "vendor.rild.",
            "vendor.hw_mc.esim.",
            "vendor.hw_mc.radio.",
            "vendor.hw_mc.ril.",
            "persist.odm.config.",
        ):
            self.assertTrue(
                any(
                    line.split() == [prop_key, "u:object_r:vendor_rild_prop:s0"]
                    for line in vnd_prop_ctx
                    if line.strip() and not line.lstrip().startswith("#")
                ),
                f"Missing {prop_key} in vendor/property_contexts",
            )
        self.assertTrue(
            any(
                line.split() == ["vendor.hw_mc.aps.", "u:object_r:vendor_display_prop:s0"]
                for line in vnd_prop_ctx
                if line.strip() and not line.lstrip().startswith("#")
            ),
            "Missing vendor.hw_mc.aps. -> vendor_display_prop in vendor/property_contexts",
        )

        widevine_te = re.sub(r"#.*", "", (VENDOR / "vendor_hal_drm_widevine.te").read_text())
        self.assertIn("allow vendor_hal_drm_widevine vendor_mediadrm_data_file:file create_file_perms;", widevine_te)

        conn_te = re.sub(r"#.*", "", (VENDOR / "vendor_hisi_connectivity.te").read_text())
        self.assertIn("set_prop(vendor_hisi_connectivity, vendor_ctl_gpsd_prop)", conn_te)
        self.assertIn("allow vendor_hisi_connectivity fwk_sensor_hwservice:hwservice_manager find;", conn_te)

        rild_te = re.sub(r"#.*", "", (VENDOR / "rild.te").read_text())
        for needle in (
            "set_prop(rild, vendor_rild_prop)",
            "allow rild vendor_modem_block_device:blk_file r_file_perms;",
            "get_prop(rild, telephony_config_prop)",
            "dontaudit rild { default_prop config_prop system_prop }:file r_file_perms;",
            "dontaudit rild shell_data_file:dir search;",
            "r_dir_file(rild, sysfs_sim_hotplug)",
            "allow rild sysfs_sim_hotplug:file rw_file_perms;",
            "hal_client_domain(rild, vendor_hal_hisi_libteec)",
            "hal_client_domain(rild, vendor_hal_hisi_hwfactoryinterface)",
        ):
            self.assertIn(needle, rild_te)

        tee_te = re.sub(r"#.*", "", (VENDOR / "tee.te").read_text())
        self.assertEqual(len(re.findall(r"(?m)^\s*rild$", tee_te)), 3)

    def test_attempt9_camera_ril_hwc_bt_gps_sepolicy_closure(self):
        decls = load_type_declarations()
        for fs_type in ("sysfs_sim_hotplug", "sysfs_hisi_connectivity"):
            self.assertIn("fs_type", decls[fs_type])

        prop_te = (VENDOR / "property.te").read_text()
        self.assertIn("vendor_internal_prop(vendor_display_prop)", prop_te)

        rules = load_file_contexts()
        self.assertEqual(resolve_type(rules, "/dev/madapt"), "radio_device")
        self.assertEqual(resolve_type(rules, "/dev/rpmsg-hisi0"), "camera_device")
        self.assertEqual(resolve_type(rules, "/dev/voice_proxy_ctl_dev"), "audio_device")
        self.assertEqual(resolve_type(rules, "/sys/nfc/nfc_chip_type"), "sysfs_nfc_power_writable")

        genfs_txt = (VENDOR / "genfs_contexts").read_text()
        for needle in (
            "genfscon proc /bluetooth_power/data/calibrate u:object_r:proc_bluetooth_writable:s0",
            "genfscon sysfs /firmware/devicetree/base/hisi,camera_arch_csudp u:object_r:sysfs_camerafs:s0",
            "genfscon sysfs /firmware/devicetree/base/hisi,hisi_camera u:object_r:sysfs_camerafs:s0",
            "genfscon sysfs /firmware/devicetree/base/huawei,camcfgdev u:object_r:sysfs_camerafs:s0",
            "genfscon sysfs /firmware/devicetree/base/huawei,camera_ldo u:object_r:sysfs_camerafs:s0",
            "genfscon sysfs /firmware/devicetree/base/huawei,hicam_buf u:object_r:sysfs_camerafs:s0",
            "genfscon sysfs /firmware/devicetree/base/smmu_cam u:object_r:sysfs_camerafs:s0",
            "genfscon sysfs /kernel/sim u:object_r:sysfs_sim_hotplug:s0",
            "genfscon sysfs /firmware/devicetree/base/bcm_wifi u:object_r:sysfs_hisi_connectivity:s0",
            "genfscon sysfs /firmware/devicetree/base/bcm_bt u:object_r:sysfs_hisi_connectivity:s0",
            "genfscon sysfs /firmware/devicetree/base/gps_power u:object_r:sysfs_hisi_connectivity:s0",
            "genfscon sysfs /devices/platform/amba/fdf0d000.i2c/i2c-4/4-0028 u:object_r:sysfs_nfc_power_writable:s0",
        ):
            self.assertIn(needle, genfs_txt)

        pub_libteec = re.sub(r"#.*", "", (POLICY / "public/vendor_hal_hisi_libteec.te").read_text())
        self.assertIn("allow vendor_hal_hisi_libteec_client vendor_hal_hisi_libteec_server:fd use;", pub_libteec)
        self.assertIn("allow vendor_hal_hisi_libteec_server vendor_hal_hisi_libteec_client:fd use;", pub_libteec)

        clients_te = re.sub(r"#.*", "", (VENDOR / "hal_hisi_clients.te").read_text())
        for needle in (
            "get_prop(hal_graphics_composer_default, vendor_display_prop)",
            "set_prop(vendor_hal_hisi_display_default, vendor_display_prop)",
            "get_prop({ hal_graphics_composer_default hal_graphics_allocator_default }, vendor_camera_prop)",
            "hal_client_domain(hal_camera_default, hal_camera)",
            "hal_client_domain(hal_camera_default, vendor_hal_hisi_libteec)",
            "hal_client_domain(hal_camera_default, vendor_hal_hisi_hwfactoryinterface)",
            "binder_call(hal_camera_default, system_server)",
            "binder_call(system_server, hal_camera_default)",
            "r_dir_file(hal_camera_default, { sysfs_pg sysfs_camerafs sysfs_huawei_sensor sysfs_leds sysfs_devfreq_dir })",
            "dontaudit hal_camera_default default_prop:file r_file_perms;",
            "allow hal_audio_default radio_device:chr_file rw_file_perms;",
            "r_dir_file(hal_gnss_default, sysfs_hisi_connectivity)",
            "r_dir_file(hal_secure_element_default, sysfs_nfc_power_writable)",
        ):
            self.assertIn(needle, clients_te)

        init_te = re.sub(r"#.*", "", (VENDOR / "init.te").read_text())
        self.assertIn("r_dir_file(init, sysfs_hisi_connectivity)", init_te)

        conn_te = re.sub(r"#.*", "", (VENDOR / "vendor_hisi_connectivity.te").read_text())
        self.assertIn("allow vendor_hisi_connectivity self:netlink_kobject_uevent_socket create_socket_perms_no_ioctl;", conn_te)
        self.assertIn("dontaudit vendor_hisi_connectivity shell_data_file:dir search;", conn_te)


if __name__ == "__main__":
    unittest.main()
