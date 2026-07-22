#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reproduce/verify this public prebuilt adaptation; does not build target providers."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent
REMOVED = ("libhidltransport.so", "libhwbinder.so")
SOURCE_GUARDS = {
    # Reviewed empty carriers, merged transport sources and whole-static binder implementation.
    "system/libhidl/Android.bp": "6e997721960773fce46b2fb0d15adea77ab7e99a1f82aeed43635ae4db8fa889",
    "system/libhwbinder/Android.bp": "3f67184794506d28d40021bff94f2eafd0879dbc51374a779330f369622666ef",
    "system/core/libsync/libsync.map.txt": "977e84fa5f63dc8038284a99966d77e6934694c17e2a6c87fc225dd36015d6d8",
    "prebuilts/extract-tools/linux-x86/bin/patchelf-0_9": "8c71725a3c6db7e51b6fa757e9e6601af479de132747fe41b8fab04bcda47efc",
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def allocated_sections(path):
    """Hash every allocated section except the two intentionally edited metadata sections."""
    data = path.read_bytes()
    require(data[:4] == b"\x7fELF" and data[5] == 1, "Expected little-endian ELF")
    if data[4] == 1:
        offset = struct.unpack_from("<I", data, 32)[0]
        size, count, names_index = struct.unpack_from("<HHH", data, 46)
        fmt = "<IIIIIIIIII"
    else:
        require(data[4] == 2, "Expected ELF32 or ELF64")
        offset = struct.unpack_from("<Q", data, 40)[0]
        size, count, names_index = struct.unpack_from("<HHH", data, 58)
        fmt = "<IIQQQQIIQQ"
    headers = [struct.unpack_from(fmt, data, offset + i * size) for i in range(count)]
    names_header = headers[names_index]
    names = data[names_header[4]:names_header[4] + names_header[5]]
    result = {}
    for h in headers:
        name = names[h[0]:].split(b"\0", 1)[0].decode("ascii")
        if not h[2] & 2 or name in (".dynamic", ".dynstr"):
            continue
        content = None if h[1] == 8 else hashlib.sha256(data[h[4]:h[4] + h[5]]).hexdigest()
        if h[1] == 11:
            # patchelf renumbers section headers. Preserve every dynamic symbol's
            # value/size/binding/visibility and resolved section, not its raw index.
            symbols = []
            for position in range(h[4], h[4] + h[5], h[9]):
                if data[4] == 1:
                    n, value, length, info, other, index = struct.unpack_from("<IIIBBH", data, position)
                else:
                    n, info, other, index, value, length = struct.unpack_from("<IBBHQQ", data, position)
                section = (names[headers[index][0]:].split(b"\0", 1)[0].decode("ascii")
                           if 0 < index < count else index)
                symbols.append((n, value, length, info, other, section))
            content = hashlib.sha256(json.dumps(symbols).encode()).hexdigest()
        result[name] = {"address": h[3], "bytes": h[5], "type": h[1], "sha256": content}
    return result


def verify_sources(top):
    for name, expected in SOURCE_GUARDS.items():
        require(sha256(top / name) == expected, "Source/tool contract changed: " + name)


def transform(source, destination, expected, patch_hash, tool, llvm, checker):
    require(sha256(source) == expected, "Unexpected public VNDK29 input")
    before = checker.ELFParser.open(str(source), str(llvm))
    require(before.dt_soname == "libui.so" and "libhidlbase.so" in before.dt_needed,
            "Unexpected source SONAME/provider")
    require(all(before.dt_needed.count(n) == 1 for n in REMOVED), "Obsolete dependencies changed")
    shutil.copyfile(source, destination)
    subprocess.run([str(tool), "--set-soname", destination.name, str(destination)], check=True)
    require(sha256(destination) == patch_hash, "Maintainer SONAME-only reproduction differs")
    for name in REMOVED:
        subprocess.run([str(tool), "--remove-needed", name, str(destination)], check=True)
    after = checker.ELFParser.open(str(destination), str(llvm))
    require(after.dt_soname == "libui-v29.so", "Wrong final SONAME")
    require(after.dt_needed == [n for n in before.dt_needed if n not in REMOVED],
            "Unexpected dependency transformation")
    require(before.imported == after.imported and before.exported == after.exported,
            "Import/export contract changed")
    sections = allocated_sections(source)
    require(sections == allocated_sections(destination), "Allocated code/data changed")
    require(all(n % 4096 == 0 for n in after.alignments), "Unexpected load alignment")
    return {"input_sha256": expected, "maintainer_sha256": patch_hash,
            "output_sha256": sha256(destination), "bytes": destination.stat().st_size,
            "needed": after.dt_needed, "imports": len(after.imported),
            "exports": len(after.exported), "unchanged_allocated_sections": sections}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workspace", type=Path)
    parser.add_argument("--write", action="store_true", help="create missing reproducible files")
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    top = workspace / "src/lineage-24.0"
    snapshot = workspace / "src/twrp-14.1/prebuilts/vndk/v29"
    audit = json.loads((ROOT.parent / "audits/libui-v29-20261114.json").read_text())
    verify_sources(top)
    spec = importlib.util.spec_from_file_location("elf_checker", top / "build/make/tools/check_elf_file.py")
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    llvm = top / "prebuilts/clang/host/linux-x86/clang-r596125/bin/llvm-readobj"
    tool = top / "prebuilts/extract-tools/linux-x86/bin/patchelf-0_9"
    report = {"source_guards": SOURCE_GUARDS, "removed_needed": list(REMOVED), "abis": {}}
    with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
        outputs = []
        for abi, arch in (("arm", "arch-arm-armv8-a"), ("arm64", "arch-arm64-armv8-a")):
            directory = Path(temporary) / abi
            directory.mkdir()
            output = directory / "libui-v29.so"
            row = audit["abis"][abi]
            report["abis"][abi] = transform(
                snapshot / "arm64" / arch / "shared/vndk-core/libui.so", output,
                row["files"]["aosp_snapshot"]["sha256"], row["files"]["candidate"]["sha256"],
                tool, llvm, checker)
            outputs.append((output, ROOT / abi / output.name))
        notice = snapshot / "common/NOTICE_FILES/libui.so.txt"
        require(sha256(notice) == audit["aosp_vndk29"]["notice_sha256"], "Upstream notice changed")
        outputs.append((notice, ROOT / "NOTICE"))
        # Validate all existing destinations before creating any files; never replace dirty bytes.
        for source, target in outputs:
            if target.exists():
                require(target.read_bytes() == source.read_bytes(), "Changed output: " + str(target))
            else:
                require(args.write, "Missing output: " + str(target))
        if args.write:
            for source, target in outputs:
                if not target.exists():
                    target.parent.mkdir(exist_ok=True)
                    shutil.copyfile(source, target)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
