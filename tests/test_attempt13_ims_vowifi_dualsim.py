# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
"""Host compilation and behavioral unit tests for Attempt 13 IMS/VoWiFi/Dual-SIM shims."""

from __future__ import annotations

import pathlib
import subprocess
import tempfile
import unittest

HW_ROOT = pathlib.Path(__file__).resolve().parents[1]
SHIM_DIR = HW_ROOT / "ims/shim"


def _find_android_root() -> pathlib.Path | None:
    for cand in (
        HW_ROOT.parents[2] / "src/lineage-24.0",
        HW_ROOT.parents[1],
    ):
        if (cand / "prebuilts/jdk/jdk21/linux-x86/bin/javac").is_file():
            return cand
    return None


JAVA_HARNESS = r"""
package com.huawei.ims;

import android.content.Intent;
import android.os.SystemProperties;
import android.telephony.CarrierConfigManager;
import android.telephony.RadioAccessFamily;
import android.telephony.TelephonyManager;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import com.android.internal.telephony.HwModemCapability;
import com.huawei.android.net.wifi.WifiManagerEx;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

public final class Attempt13ShimTestHarness {
    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
        }
    }

    static final class FakeEnv implements HwImsShim.DualSimEnv {
        List<HwImsShim.SimSubSlot> subs = new ArrayList<>();
        int ddsSubId = -1;
        boolean airplaneOff = true;
        int phoneCount = 2;
        boolean[] radioAvail = {true, true};
        boolean[] radioOn = {true, true};
        int callState = TelephonyManager.CALL_STATE_IDLE;
        String[] modemUuid = {"0", "1"};
        int[] rafs = {0x7FFF, 0x01FF};
        boolean acceptSetRaf = true;

        final List<Integer> assignedDds = new ArrayList<>();
        final List<Integer> forcedPowerSlots = new ArrayList<>();
        final List<Integer> resyncedImsSlots = new ArrayList<>();
        final List<RadioAccessFamily[]> rafCalls = new ArrayList<>();

        @Override
        public List<HwImsShim.SimSubSlot> getActiveSubscriptions() {
            return subs;
        }

        @Override
        public int getDefaultDataSubId() {
            return ddsSubId;
        }

        @Override
        public boolean isValidSubId(int subId) {
            return subId >= 1;
        }

        @Override
        public int getSlotIndex(int subId) {
            for (HwImsShim.SimSubSlot s : subs) {
                if (s.subId == subId) {
                    return s.slotId;
                }
            }
            return -1;
        }

        @Override
        public void setDefaultDataSubId(int subId) {
            assignedDds.add(subId);
        }

        @Override
        public boolean isAirplaneModeOff() {
            return airplaneOff;
        }

        @Override
        public int getPhoneCount() {
            return phoneCount;
        }

        @Override
        public boolean isRadioAvailable(int slotId) {
            return radioAvail[slotId];
        }

        @Override
        public boolean isRadioOn(int slotId) {
            return radioOn[slotId];
        }

        @Override
        public void forceRadioPowerOn(int slotId) {
            forcedPowerSlots.add(slotId);
        }

        @Override
        public void resyncImsSwitch(int slotId) {
            resyncedImsSlots.add(slotId);
        }

        @Override
        public int getCallState() {
            return callState;
        }

        @Override
        public String getModemUuid(int slotId) {
            return modemUuid[slotId];
        }

        @Override
        public int getPhoneRaf(int slotId) {
            return rafs[slotId];
        }

        @Override
        public boolean setRadioCapability(RadioAccessFamily[] req) {
            rafCalls.add(req);
            return acceptSetRaf;
        }
    }

    private static void testModemCapabilityBit21() {
        // Kirin 970 persist.radio.modem.cap = "09B9D52" -> bit 21 (char idx 5 = '5' = 0b0101, mask 0x4) == 1
        assertTrue(
                HwModemCapability.isCapabilitySupport(21),
                "Expected HwModemCapability bit 21 (SECONDARY_IMS) supported on default 09B9D52");
        assertTrue(
                !HwModemCapability.isCapabilitySupport(300),
                "Out-of-range capability must return false");
    }

    private static void testDualSimUnsetDdsAndHotplugAndRafSwap() {
        HwImsShim.resetDualSimStateForTest();
        FakeEnv env = new FakeEnv();
        env.subs =
                Arrays.asList(
                        new HwImsShim.SimSubSlot(1, 3, true),
                        new HwImsShim.SimSubSlot(0, 2, true));
        env.ddsSubId = -1;
        env.radioOn[0] = true;
        env.radioOn[1] = false; // Slot 1 stuck RADIO_OFF after 1->2 SIM hot-plug

        HwImsShim.reconcileDualSimState(env, 5_000L);
        assertEquals(Arrays.asList(2), env.assignedDds, "Must auto-select Slot 0 subId=2 when DDS=-1");
        assertEquals(Arrays.asList(1), env.forcedPowerSlots, "Must kick RADIO_POWER(true) on RADIO_OFF Slot 1");
        assertEquals(Arrays.asList(1), env.resyncedImsSlots, "Must resync IMS switch on recovered Slot 1");
        assertEquals(0, env.rafCalls.size(), "Must defer RAF swap while DDS was unset / radio power kicked");

        // Debounce check within 3s window
        HwImsShim.reconcileDualSimState(env, 6_000L);
        assertEquals(1, env.forcedPowerSlots.size(), "RADIO_POWER kick must be debounced within 3s");

        // Now Slot 1 is RADIO_ON and user selects Slot 1 (subId=3) as Default Data SIM while Slot 0 holds modem "0"
        env.radioOn[1] = true;
        env.ddsSubId = 3;
        env.modemUuid[0] = "0";
        env.modemUuid[1] = "1";
        env.rafs[0] = 0x7FFF; // LTE+WCDMA+GSM (modem0)
        env.rafs[1] = 0x01FF; // WCDMA+GSM (modem1)
        env.resyncedImsSlots.clear();

        HwImsShim.reconcileDualSimState(env, 9_000L);
        assertEquals(1, env.rafCalls.size(), "Must invoke ProxyController.setRadioCapability when DDS is on modem1");
        RadioAccessFamily[] req = env.rafCalls.get(0);
        assertEquals(0, req[0].getPhoneId(), "Phone 0 entry");
        assertEquals(0x01FF, req[0].getRadioAccessFamily(), "Phone 0 gets modem1 RAF");
        assertEquals(1, req[1].getPhoneId(), "Phone 1 entry");
        assertEquals(0x7FFF, req[1].getRadioAccessFamily(), "DDS Phone 1 gets modem0 LTE RAF");
        assertEquals(Arrays.asList(0, 1), env.resyncedImsSlots, "Both slots resync IMS switch after RAF swap");

        // Once Slot 1 owns modem "0", subsequent pass after debounce is a strict no-op
        env.modemUuid[0] = "1";
        env.modemUuid[1] = "0";
        env.rafs[0] = 0x01FF;
        env.rafs[1] = 0x7FFF;
        HwImsShim.reconcileDualSimState(env, 25_000L);
        assertEquals(1, env.rafCalls.size(), "Must be a no-op when DDS slot already owns modem0");

        // Never kick disabled SIM or during Airplane Mode or swap during active call
        HwImsShim.resetDualSimStateForTest();
        FakeEnv env2 = new FakeEnv();
        env2.subs = Arrays.asList(new HwImsShim.SimSubSlot(0, 2, false), new HwImsShim.SimSubSlot(1, 3, true));
        env2.ddsSubId = 3;
        env2.radioOn[0] = false;
        env2.radioOn[1] = false;
        env2.airplaneOff = false;
        HwImsShim.reconcileDualSimState(env2, 5_000L);
        assertEquals(0, env2.forcedPowerSlots.size(), "Must not force RADIO_ON in Airplane Mode");

        env2.airplaneOff = true;
        env2.radioOn[1] = true;
        env2.callState = TelephonyManager.CALL_STATE_OFFHOOK;
        HwImsShim.reconcileDualSimState(env2, 10_000L);
        assertEquals(0, env2.forcedPowerSlots.size(), "Must not force RADIO_ON on user-disabled SIM (uiccAppsEnabled=false)");
        assertEquals(0, env2.rafCalls.size(), "Must not swap RAF during active voice call");
    }

    private static void testWifiSignalStructAndHexHelpers() throws Exception {
        byte[] raw = WifiManagerEx.fetchWifiSignalInfoForVoWiFi();
        assertEquals(60, raw.length, "WifiManagerEx must return 60-byte WifiSignalInfo prefix");
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int rssi = bb.getInt(0);
        int linkSpeed = bb.getInt(12);
        int freq = bb.getInt(20);
        long txGood = bb.getLong(24);
        String bssid = new String(raw, 40, 12, StandardCharsets.US_ASCII);
        assertTrue(rssi < 0, "RSSI must be negative so libvowifiqoe CheckSignalInfoInvalid sign check passes");
        assertTrue(linkSpeed > 0 && freq > 0 && txGood >= 1L, "Positive linkSpeed/freq/txGood");
        assertTrue(!bssid.equals("000000000000") && bssid.length() == 12, "Valid 12-char non-zero BSSID");

        Class<?> bridgeCls = Class.forName("com.huawei.ims.shim.HisiRadioBridge");
        Method b2h = bridgeCls.getDeclaredMethod("bytesToHex", byte[].class);
        Method h2b = bridgeCls.getDeclaredMethod("hexToBytes", String.class);
        b2h.setAccessible(true);
        h2b.setAccessible(true);
        byte[] rand = new byte[16];
        for (int i = 0; i < 16; i++) rand[i] = (byte) (0xA0 + i);
        String hex = (String) b2h.invoke(null, (Object) rand);
        assertEquals(32, hex.length(), "16-byte RAND must encode to 32 ASCII hex chars for AT^UICCAUTH");
        byte[] roundtrip = (byte[]) h2b.invoke(null, hex);
        assertTrue(Arrays.equals(rand, roundtrip), "Hex roundtrip must match exact bytes");

        // Verify WifiManagerEx TrafficStats supplier override & non-throwing 1L floor.
        Field supField = WifiManagerEx.class.getDeclaredField("sWlan0TxPacketsSupplier");
        supField.setAccessible(true);
        LongSupplier origSup = (LongSupplier) supField.get(null);
        try {
            supField.set(null, (LongSupplier) () -> 4242L);
            byte[] customRaw = WifiManagerEx.fetchWifiSignalInfoForVoWiFi();
            long customTx = ByteBuffer.wrap(customRaw).order(ByteOrder.LITTLE_ENDIAN).getLong(24);
            assertEquals(4242L, customTx, "WifiManagerEx must propagate positive TrafficStats count");

            supField.set(null, (LongSupplier) () -> 0L);
            byte[] zeroRaw = WifiManagerEx.fetchWifiSignalInfoForVoWiFi();
            long zeroTx = ByteBuffer.wrap(zeroRaw).order(ByteOrder.LITTLE_ENDIAN).getLong(24);
            assertEquals(1L, zeroTx, "WifiManagerEx must clamp zero tx_packets to 1L");

            supField.set(
                    null,
                    (LongSupplier) () -> {
                        throw new RuntimeException("simulated stats failure");
                    });
            byte[] errRaw = WifiManagerEx.fetchWifiSignalInfoForVoWiFi();
            long errTx = ByteBuffer.wrap(errRaw).order(ByteOrder.LITTLE_ENDIAN).getLong(24);
            assertEquals(1L, errTx, "WifiManagerEx must clamp stats exceptions to 1L");
        } finally {
            supField.set(null, origSup);
        }
    }

    private static void testCarrierConfigAndRegTechHelpers() {
        assertEquals(-1, HwImsShim.extractCarrierConfigSlotId(null), "Null intent slotId");

        Intent bothExtras = new Intent();
        bothExtras.putExtra(CarrierConfigManager.EXTRA_SLOT_INDEX, 1);
        bothExtras.putExtra("phone", 0);
        assertEquals(
                1,
                HwImsShim.extractCarrierConfigSlotId(bothExtras),
                "EXTRA_SLOT_INDEX must take precedence over phone");

        Intent legacyPhoneExtra = new Intent();
        legacyPhoneExtra.putExtra("phone", 1);
        assertEquals(
                1,
                HwImsShim.extractCarrierConfigSlotId(legacyPhoneExtra),
                "Legacy phone extra fallback");

        assertEquals(
                -1,
                HwImsShim.extractCarrierConfigSlotId(new Intent()),
                "Intent without slot extras");

        Intent emuiLoaded = new Intent();
        emuiLoaded.putExtra("carrierConfigChangeState", 1);
        assertTrue(
                HwImsShim.isCarrierConfigChangeLoaded(null, 0, emuiLoaded),
                "EMUI carrierConfigChangeState=1 must be treated as loaded");

        Intent emuiUnloaded = new Intent();
        emuiUnloaded.putExtra("carrierConfigChangeState", 0);
        emuiUnloaded.putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, 2);
        assertTrue(
                !HwImsShim.isCarrierConfigChangeLoaded(null, 0, emuiUnloaded),
                "Explicit EMUI carrierConfigChangeState=0 must stay false");

        Intent aospLoaded = new Intent();
        aospLoaded.putExtra(CarrierConfigManager.EXTRA_SLOT_INDEX, 0);
        aospLoaded.putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, 2);
        assertTrue(
                HwImsShim.isCarrierConfigChangeLoaded(null, 0, aospLoaded),
                "AOSP CARRIER_CONFIG_CHANGED with valid subId must load HwIms");

        Intent aospLegacySub = new Intent();
        aospLegacySub.putExtra("subscription", 1);
        assertTrue(
                HwImsShim.isCarrierConfigChangeLoaded(null, 1, aospLegacySub),
                "CARRIER_CONFIG_CHANGED with legacy subscription extra must load HwIms");

        Intent aospNoSim = new Intent();
        aospNoSim.putExtra(CarrierConfigManager.EXTRA_SLOT_INDEX, 0);
        assertTrue(
                !HwImsShim.isCarrierConfigChangeLoaded(null, 0, aospNoSim),
                "AOSP CARRIER_CONFIG_CHANGED without subId must not mark loaded");

        assertTrue(
                !HwImsShim.isCarrierConfigReadyForSlot(null, 0),
                "Null context must not report CarrierConfig ready");
        assertTrue(
                !HwImsShim.isCarrierConfigReadyForSlot(null, -1),
                "Negative slot must not report CarrierConfig ready");
        assertTrue(
                !HwImsShim.isCarrierConfigChangeLoaded(null, 0, null),
                "Null context and null intent must return false");

        ImsRegistrationImplBase reg = new ImsRegistrationImplBase();
        HwImsShim.notifyRegistrationTechUpdate(null, 1, ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        HwImsShim.notifyRegistrationTechUpdate(reg, 0, ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        assertEquals(
                Collections.emptyList(),
                reg.registeredTechs,
                "LTE service update while unregistered must not call onRegistered");

        HwImsShim.notifyRegistrationTechUpdate(
                reg, 0, ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
        HwImsShim.notifyRegistrationTechUpdate(reg, 1, ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        HwImsShim.notifyRegistrationTechUpdate(
                reg, 1, ImsRegistrationImplBase.REGISTRATION_TECH_NONE);
        assertEquals(
                Arrays.asList(
                        ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN,
                        ImsRegistrationImplBase.REGISTRATION_TECH_LTE),
                reg.registeredTechs,
                "IWLAN and registered LTE transitions must update ImsRegistrationImplBase tech");
    }

    public static void main(String[] args) throws Exception {
        testModemCapabilityBit21();
        testDualSimUnsetDdsAndHotplugAndRafSwap();
        testWifiSignalStructAndHexHelpers();
        testCarrierConfigAndRegTechHelpers();
        System.out.println("ATTEMPT13_SHIM_TESTS_OK");
    }
}
"""


class Attempt13ShimHostTests(unittest.TestCase):
    def test_cold_compile_and_behavioral_harness(self):
        android_root = _find_android_root()
        if android_root is None:
            self.skipTest("Android prebuilts tree not mounted adjacent to hardware/huawei")

        javac = android_root / "prebuilts/jdk/jdk21/linux-x86/bin/javac"
        java = android_root / "prebuilts/jdk/jdk21/linux-x86/bin/java"
        modlib = android_root / "prebuilts/sdk/current/module-lib"
        cold_cp = ":".join(
            str(modlib / j)
            for j in (
                "android.jar",
                "framework-connectivity.jar",
                "framework-connectivity-t.jar",
                "framework-wifi.jar",
            )
        )

        with tempfile.TemporaryDirectory() as td:
            tdp = pathlib.Path(td)
            stub_cls = tdp / "stub_cls"
            shim_cls = tdp / "shim_cls"
            test_src_dir = tdp / "test_src/com/huawei/ims"
            stub_cls.mkdir()
            shim_cls.mkdir()
            test_src_dir.mkdir(parents=True)

            # Minimal SystemProperties host stub so pure JVM execution does not hit android.jar Stub!
            sysprop_src = tdp / "test_src/android/os"
            sysprop_src.mkdir(parents=True)
            (sysprop_src / "SystemProperties.java").write_text(
                "package android.os;\n"
                "public class SystemProperties {\n"
                "    public static String get(String k, String d) {\n"
                "        return \"persist.radio.modem.cap\".equals(k) ? \"09B9D52\" : d;\n"
                "    }\n"
                "    public static boolean getBoolean(String k, boolean d) { return d; }\n"
                "    public static int getInt(String k, int d) { return d; }\n"
                "}\n",
                encoding="utf-8",
            )
            log_src = tdp / "test_src/android/util"
            log_src.mkdir(parents=True)
            (log_src / "Log.java").write_text(
                "package android.util;\n"
                "public final class Log {\n"
                "    public static int d(String t, String m) { return 0; }\n"
                "    public static int i(String t, String m) { return 0; }\n"
                "    public static int w(String t, String m) { return 0; }\n"
                "    public static int w(String t, String m, Throwable e) { return 0; }\n"
                "    public static int e(String t, String m) { return 0; }\n"
                "    public static int e(String t, String m, Throwable e) { return 0; }\n"
                "}\n",
                encoding="utf-8",
            )
            intent_src = tdp / "test_src/android/content"
            intent_src.mkdir(parents=True)
            (intent_src / "Intent.java").write_text(
                "package android.content;\n"
                "import java.util.HashMap;\n"
                "import java.util.Map;\n"
                "public class Intent {\n"
                "    public static final String ACTION_AIRPLANE_MODE_CHANGED = \"android.intent.action.AIRPLANE_MODE\";\n"
                "    private final Map<String, Integer> intExtras = new HashMap<>();\n"
                "    public boolean hasExtra(String name) { return intExtras.containsKey(name); }\n"
                "    public int getIntExtra(String name, int def) {\n"
                "        Integer v = intExtras.get(name);\n"
                "        return v != null ? v.intValue() : def;\n"
                "    }\n"
                "    public Intent putExtra(String name, int value) {\n"
                "        intExtras.put(name, value);\n"
                "        return this;\n"
                "    }\n"
                "}\n",
                encoding="utf-8",
            )
            ccm_src = tdp / "test_src/android/telephony"
            ccm_src.mkdir(parents=True)
            (ccm_src / "CarrierConfigManager.java").write_text(
                "package android.telephony;\n"
                "import android.os.PersistableBundle;\n"
                "public class CarrierConfigManager {\n"
                "    public static final String EXTRA_SLOT_INDEX = \"android.telephony.extra.SLOT_INDEX\";\n"
                "    public static final String EXTRA_SUBSCRIPTION_INDEX = \"android.telephony.extra.SUBSCRIPTION_INDEX\";\n"
                "    public PersistableBundle getConfigForSubId(int subId) { return null; }\n"
                "    public static boolean isConfigForIdentifiedCarrier(PersistableBundle b) { return false; }\n"
                "}\n",
                encoding="utf-8",
            )
            reg_src = tdp / "test_src/android/telephony/ims/stub"
            reg_src.mkdir(parents=True)
            (reg_src / "ImsRegistrationImplBase.java").write_text(
                "package android.telephony.ims.stub;\n"
                "import java.util.ArrayList;\n"
                "import java.util.List;\n"
                "public class ImsRegistrationImplBase {\n"
                "    public static final int REGISTRATION_TECH_NONE = -1;\n"
                "    public static final int REGISTRATION_TECH_LTE = 0;\n"
                "    public static final int REGISTRATION_TECH_IWLAN = 1;\n"
                "    public final List<Integer> registeredTechs = new ArrayList<>();\n"
                "    public final void onRegistered(int tech) { registeredTechs.add(tech); }\n"
                "}\n",
                encoding="utf-8",
            )
            harness_src = test_src_dir / "Attempt13ShimTestHarness.java"
            harness_src.write_text(JAVA_HARNESS, encoding="utf-8")

            stubs = sorted(str(p) for p in (SHIM_DIR / "compile_stubs").rglob("*.java"))
            srcs = sorted(str(p) for p in (SHIM_DIR / "src").rglob("*.java"))

            subprocess.run(
                [str(javac), "--release", "11", "-cp", cold_cp, "-d", str(stub_cls), *stubs],
                check=True,
            )
            subprocess.run(
                [
                    str(javac),
                    "--release",
                    "11",
                    "-cp",
                    f"{cold_cp}:{stub_cls}",
                    "-d",
                    str(shim_cls),
                    *srcs,
                ],
                check=True,
            )
            subprocess.run(
                [
                    str(javac),
                    "--release",
                    "11",
                    "-cp",
                    f"{shim_cls}:{stub_cls}:{cold_cp}",
                    "-d",
                    str(shim_cls),
                    str(sysprop_src / "SystemProperties.java"),
                    str(log_src / "Log.java"),
                    str(intent_src / "Intent.java"),
                    str(ccm_src / "CarrierConfigManager.java"),
                    str(reg_src / "ImsRegistrationImplBase.java"),
                    str(harness_src),
                ],
                check=True,
            )
            res = subprocess.run(
                [
                    str(java),
                    "-cp",
                    f"{shim_cls}:{stub_cls}:{cold_cp}",
                    "com.huawei.ims.Attempt13ShimTestHarness",
                ],
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, res.returncode, f"Harness failed:\n{res.stdout}\n{res.stderr}")
            self.assertIn("ATTEMPT13_SHIM_TESTS_OK", res.stdout)


if __name__ == "__main__":
    unittest.main()
