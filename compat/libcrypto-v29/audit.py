#!/usr/bin/env python3
"""Verify VNDK-29 libcrypto-v29.so provenance, ELF headers, and consumer closure."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

EXPECTED_SNAPSHOT_SHA256 = (
    "7161d6cf9e6feb6d2a5a8684c6bed9f7dd786490987fd00e240d269665e98215"
)
EXPECTED_LICENSE_SHA256 = (
    "60bd7c54856bf9387221bde5ab55d516d7cea15870d0fed69406bcd1c8ec7c9d"
)
EXPECTED_CANDIDATE_SHA256 = (
    "bfa372be0d226d400f84855e9058260acddf494acc28981b6b30a6f32f3f2433"
)
EXPECTED_DT_NEEDED = ["libc.so", "libm.so", "libdl.so"]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def require(cond: bool, msg: str) -> None:
    if not cond:
        raise RuntimeError(msg)


def find_workspace(explicit: Path | None) -> Path:
    if explicit is not None:
        return explicit.resolve()
    for base in (Path.cwd(), *Path(__file__).resolve().parents):
        if (base / "src/lineage-24.0").is_dir() and (base / "src/twrp-14.1").is_dir():
            return base
    raise RuntimeError("Could not locate workspace root; pass --workspace")


def load_checker(checker_path: Path):
    spec = importlib.util.spec_from_file_location("check_elf_file", checker_path)
    require(spec is not None and spec.loader is not None, f"Cannot load {checker_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(cmd: list[str | Path]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(x) for x in cmd],
        text=True,
        capture_output=True,
        check=False,
    )


def find_stock_lib(firmware: Path, rel_candidates: tuple[str, ...]) -> Path:
    for rel in rel_candidates:
        p = firmware / rel
        if p.is_file():
            return p
    raise RuntimeError(f"Missing firmware lib among {rel_candidates} under {firmware}")


def main() -> None:
    module_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=None)
    parser.add_argument("--firmware", type=Path, default=None)
    parser.add_argument("--snapshot", type=Path, default=None)
    parser.add_argument("--candidate", type=Path, default=module_dir / "arm64/libcrypto-v29.so")
    parser.add_argument("--output", type=Path, default=module_dir / "AUDIT.json")
    args = parser.parse_args()

    ws = find_workspace(args.workspace)
    firmware = (args.firmware or (ws / "firmware/current/original-filesystems")).resolve()
    snapshot = (
        args.snapshot
        or (
            ws
            / "src/twrp-14.1/prebuilts/vndk/v29/arm64/arch-arm64-armv8-a/shared/vndk-core/libcrypto.so"
        )
    ).resolve()
    notice_src = (
        ws / "src/twrp-14.1/prebuilts/vndk/v29/common/NOTICE_FILES/libcrypto.so.txt"
    )
    license_file = module_dir / "LICENSE"
    candidate = args.candidate.resolve()

    checker_path = ws / "src/lineage-24.0/build/make/tools/check_elf_file.py"
    llvm_readobj = (
        ws / "src/lineage-24.0/prebuilts/clang/host/linux-x86/clang-r596125/bin/llvm-readobj"
    )
    patchelf = ws / "src/lineage-24.0/prebuilts/extract-tools/linux-x86/bin/patchelf-0_9"

    for p in (firmware, snapshot, notice_src, license_file, candidate, checker_path, llvm_readobj, patchelf):
        require(p.exists(), f"Missing required path: {p}")

    snap_sha = sha256_file(snapshot)
    lic_sha = sha256_file(license_file)
    cand_sha = sha256_file(candidate)
    require(snap_sha == EXPECTED_SNAPSHOT_SHA256, f"Unexpected snapshot sha256: {snap_sha}")
    require(lic_sha == EXPECTED_LICENSE_SHA256, f"Unexpected LICENSE sha256: {lic_sha}")
    require(license_file.read_bytes() == notice_src.read_bytes(), "LICENSE differs from NOTICE_FILES")
    require(cand_sha == EXPECTED_CANDIDATE_SHA256, f"Unexpected candidate sha256: {cand_sha}")

    with tempfile.TemporaryDirectory(dir=module_dir) as tmpdir:
        repro = Path(tmpdir) / candidate.name
        shutil.copyfile(snapshot, repro)
        res = run([patchelf, "--set-soname", "libcrypto-v29.so", repro])
        require(res.returncode == 0, f"patchelf failed: {res.stderr}")
        require(repro.read_bytes() == candidate.read_bytes(), "Candidate does not match patchelf reproduction")

    checker = load_checker(checker_path)
    snap_elf = checker.ELFParser.open(str(snapshot), str(llvm_readobj))
    cand_elf = checker.ELFParser.open(str(candidate), str(llvm_readobj))

    require(cand_elf.dt_soname == "libcrypto-v29.so", f"Bad DT_SONAME: {cand_elf.dt_soname}")
    require(cand_elf.dt_needed == EXPECTED_DT_NEEDED, f"Bad DT_NEEDED: {cand_elf.dt_needed}")
    require(all(a % 4096 == 0 for a in cand_elf.alignments), f"Bad alignments: {cand_elf.alignments}")
    require(cand_elf.exported == snap_elf.exported, "Exported symbol table drifted from VNDK-29 snapshot")

    iked_path = firmware / "vendor/bin/iked"
    libracoon2_path = firmware / "vendor/lib64/libracoon2.so"
    stock_libssl = find_stock_lib(firmware, ("system/lib64/libssl.so", "system/system/lib64/libssl.so"))
    require(iked_path.is_file() and libracoon2_path.is_file(), "Missing firmware consumer binaries")

    iked_elf = checker.ELFParser.open(str(iked_path), str(llvm_readobj))
    libracoon2_elf = checker.ELFParser.open(str(libracoon2_path), str(llvm_readobj))
    libssl_elf = checker.ELFParser.open(str(stock_libssl), str(llvm_readobj))

    iked_crypto_imports = {
        sym: sorted(vers)
        for sym, vers in sorted(iked_elf.imported.items())
        if sym in snap_elf.exported
    }
    missing_in_candidate = [
        f"{sym}@{ver}" if ver else sym
        for sym, vers in iked_crypto_imports.items()
        for ver in (vers or [""])
        if not checker.Checker._find_symbol(cand_elf, sym, ver)
    ]
    require(not missing_in_candidate, f"Missing iked crypto symbols: {missing_in_candidate}")
    require(
        len(iked_crypto_imports) == 75,
        f"Expected 75 iked crypto imports, got {len(iked_crypto_imports)}",
    )

    racoon_crypto_or_ssl_imports = [
        sym
        for sym in sorted(libracoon2_elf.imported)
        if sym in snap_elf.exported or sym in libssl_elf.exported
    ]
    require(
        not racoon_crypto_or_ssl_imports,
        f"Unexpected libracoon2 crypto/ssl imports: {racoon_crypto_or_ssl_imports}",
    )

    bionic_deps = [
        find_stock_lib(firmware, (f"system/lib64/bootstrap/{lib}", f"system/system/lib64/bootstrap/{lib}"))
        for lib in EXPECTED_DT_NEEDED
    ]

    checker_runs = []
    pos_cmd = [
        sys.executable,
        "-B",
        checker_path,
        "--llvm-readobj",
        llvm_readobj,
        "--soname",
        "libcrypto-v29.so",
        "--max-page-size",
        "4096",
    ]
    for dep in bionic_deps:
        pos_cmd += ["--shared-lib", dep]
    pos_cmd += [candidate]
    pos_res = run(pos_cmd)
    require(pos_res.returncode == 0, f"check_elf_file positive check failed: {pos_res.stderr}")
    checker_runs.append(
        {"case": "positive_bionic_bootstrap", "exit_status": pos_res.returncode, "stderr": pos_res.stderr}
    )

    for label, extra, expected_err in (
        ("wrong_soname", ["--soname", "wrong.so"], "DT_SONAME"),
        ("undeclared_dependencies", [], "is not specified in shared_libs"),
    ):
        neg_cmd = [sys.executable, "-B", checker_path, "--llvm-readobj", llvm_readobj, *extra, candidate]
        neg_res = run(neg_cmd)
        require(
            neg_res.returncode == 2 and expected_err in neg_res.stderr,
            f"Negative check {label} unexpected: rc={neg_res.returncode} stderr={neg_res.stderr}",
        )
        checker_runs.append(
            {"case": label, "expected_exit_status": 2, "actual_exit_status": neg_res.returncode}
        )

    report = {
        "module": "libcrypto-v29",
        "snapshot": {"path": str(snapshot.relative_to(ws)), "sha256": snap_sha, "bytes": snapshot.stat().st_size},
        "license": {"path": "LICENSE", "sha256": lic_sha, "bytes": license_file.stat().st_size},
        "candidate": {
            "path": "arm64/libcrypto-v29.so",
            "sha256": cand_sha,
            "bytes": candidate.stat().st_size,
            "dt_soname": cand_elf.dt_soname,
            "dt_needed": cand_elf.dt_needed,
            "alignments": cand_elf.alignments,
            "exported_symbol_count": len(cand_elf.exported),
        },
        "aosp_soname_only_reproduction": True,
        "consumers": {
            "vendor/bin/iked": {
                "crypto_import_count": len(iked_crypto_imports),
                "missing_in_candidate": missing_in_candidate,
                "crypto_imports": iked_crypto_imports,
            },
            "vendor/lib64/libracoon2.so": {
                "crypto_or_ssl_import_count": len(racoon_crypto_or_ssl_imports),
                "crypto_or_ssl_imports": racoon_crypto_or_ssl_imports,
            },
        },
        "checker_runs": checker_runs,
    }
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(
        f"libcrypto-v29 OK: 75/75 iked crypto imports resolved; "
        f"0 libracoon2 crypto/ssl imports; check_elf_file + 2 negative checks passed"
    )


if __name__ == "__main__":
    main()
