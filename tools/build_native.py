#!/usr/bin/env python3
"""Build the pinned Android solver locally from source, without an external .a bundle.

Requires Python 3.12+ and SDK packages ndk;26.3.11579264 and cmake;3.22.1.
Verified archives are cached under --sources; --fetch downloads missing fixed archives.
Every build uses a new directory. No cleanup, credential access, or publishing.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tarfile
import uuid

ROOT = Path(__file__).resolve().parent.parent
ARCHIVES = [
    ("astrometry-0.97.tar.gz", "https://codeload.github.com/dstndstn/astrometry.net/tar.gz/refs/tags/0.97", "75a36bb0d0be827c86bedac6bcbf78f110dc19229bab1604eea403d3a0368896"),
    ("cfitsio-3.47.tar.gz", "https://ftp.eso.org/pub/dfs/pipelines/libraries/cfitsio/cfitsio-3.47.tar.gz", "418516f10ee1e0f1b520926eeca6b77ce639bed88804c7c545e74f26b3edf4ef"),
]


def run(args):
    subprocess.run([str(a) for a in args], check=True)


def make_vars(path):
    values = {}
    def expand(text):
        while "$(" in text:
            def replace(m):
                expr = m[1]
                if expr.startswith("sort "): return " ".join(sorted(set(expr[5:].split())))
                for function in ("addprefix", "addsuffix"):
                    if expr.startswith(function + " "):
                        affix, words = expr[len(function)+1:].split(",", 1)
                        return " ".join(affix+w if function == "addprefix" else w+affix for w in words.split())
                return values.get(expr, "")
            new = re.sub(r"\$\(([^()]*)\)", replace, text)
            if new == text: raise ValueError("Unsupported Make expression: " + text)
            text = new
        return text
    for line in path.read_text(encoding="utf-8").replace("\\\n", " ").splitlines():
        match = re.match(r"^(\w+)\s*(:=|\+=|=)\s*(.*)", line.split("#", 1)[0])
        if match:
            name, op, text = match.groups()
            expanded = expand(text)
            values[name] = (values.get(name, "") + " " + expanded) if op == "+=" else expanded
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path("C:/dev/android-sdk"))
    parser.add_argument("--sources", type=Path, default=ROOT / "code/app/build/native-deps")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--proxy")
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    parser.add_argument("--output", type=Path, help="Copy the verified library to this explicit file")
    args = parser.parse_args()
    args.sdk = args.sdk.resolve(); sources = args.sources.resolve()
    sources.mkdir(parents=True, exist_ok=True)
    for name, url, expected in ARCHIVES:
        archive = sources / name
        if not archive.exists() and args.fetch:
            command = ["curl.exe" if os.name == "nt" else "curl", "--fail", "--location", "--silent", "--show-error", url, "--output", archive]
            if args.proxy: command += ["--proxy", args.proxy]
            run(command)
        if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
            raise ValueError("Source archive missing or checksum mismatch: " + str(archive))
    build = ROOT / "code/app/build/native-source" / uuid.uuid4().hex
    build.mkdir(parents=True)
    # Always extract fresh, verified sources; stale objects or edited source trees are not reused.
    for name, _, _ in ARCHIVES:
        with tarfile.open(sources / name) as archive: archive.extractall(build, filter="data")
    astro = build / "astrometry.net-0.97"; cfits = build / "cfitsio-3.47"
    solver_header = astro / "include/astrometry/solver.h"
    solver_source = solver_header.read_text(encoding="utf-8")
    assert solver_source.count("anbool quit_now;") == 1
    solver_header.write_text(solver_source.replace("anbool quit_now;", "_Atomic(anbool) quit_now;"), encoding="utf-8")
    network_driver = cfits / "drvrnet.c"
    network_source = network_driver.read_text(encoding="utf-8")
    assert network_source.count("fprintf(stderr,urlname);") == 1
    network_driver.write_text(network_source.replace("fprintf(stderr,urlname);", 'fprintf(stderr,"%s",urlname);'), encoding="utf-8")
    (astro / "include/astrometry/os-features-config.h").write_text("#define HAVE_NETPBM 0\n", encoding="utf-8")
    gsl = astro / "gsl-an"
    config = (gsl / "config.h.in").read_text()
    enabled = {"STDC_HEADERS", "HAVE_INLINE", "HAVE_C99_INLINE", "HAVE_EXIT_SUCCESS_AND_FAILURE", "HAVE_IEEE_COMPARISONS", "HAVE_IEEE_DENORMALS"}
    enabled.update(re.findall(r"#undef (HAVE_\w+_H)", config))
    enabled.update({"HAVE_LIBM", "HAVE_MEMCPY", "HAVE_MEMMOVE", "HAVE_STRDUP", "HAVE_STRTOL", "HAVE_STRTOUL", "HAVE_VPRINTF", "HAVE_PRINTF_LONGDOUBLE"})
    enabled -= {"HAVE_IEEEFP_H"}
    enabled.update("HAVE_DECL_"+name for name in ("ACOSH", "ASINH", "ATANH", "EXPM1", "FREXP", "HYPOT", "ISFINITE", "ISINF", "ISNAN", "LDEXP", "LOG1P"))
    config = re.sub(r"^#undef (\w+)$", lambda m: "#define " + m[1] + " 1" if m[1] in enabled else "/* " + m[0] + " */", config, flags=re.M)
    (gsl / "config.h").write_text(config)
    libraries = {}
    for directory, targets in {"util": ("ANBASE_OBJ", "ANUTILS_OBJ", "ANFILES_OBJ"), "solver": ("ENGINE_OBJS",), "catalogs": ("OBJS",), "gsl-an": ("GSL_OBJS",), "libkd": ("KD", "KD_FITS", "INTERNALS", "DT")}.items():
        variables = make_vars(astro / directory / "Makefile")
        for target in targets:
            library = target.lower()
            files = sorted(set(variables[target].split()))
            # OpenNGC is an optional catalogue/CLI module; its generated catalogue is
            # not in the source tag and is not referenced by the plate-solving JNI API.
            if directory == "catalogs": files = [f for f in files if f != "openngc.o"]
            paths = [astro / directory / (f[:-2] + ".c") for f in files]
            for path in paths:
                if not path.is_file(): raise ValueError("Source missing: " + str(path))
            libraries[library] = paths
    variables = make_vars(astro / "qfits-an/Makefile")
    libraries["qfits"] = [astro / "qfits-an" / (name + ".c") for name in variables["FILES"].split()] + [astro / "qfits-an/md5.c"]
    bridge = ROOT / "code/app/src/main/cpp/astro_bridge.c"
    bridge_hash = hashlib.sha256(bridge.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
    lines = ["cmake_minimum_required(VERSION 3.22)", "project(StarCamNative C)", "set(CMAKE_POSITION_INDEPENDENT_CODE ON)",
             "set(BUILD_SHARED_LIBS OFF CACHE BOOL \"\" FORCE)", "set(CMAKE_DISABLE_FIND_PACKAGE_CURL TRUE)",
             'add_compile_definitions(_GNU_SOURCE _REENTRANT)',
             'add_compile_definitions(AN_GIT_URL="https://github.com/dstndstn/astrometry.net" AN_GIT_REVISION="0.97" AN_GIT_DATE="archive")',
             'add_compile_options(-O2 -g0 -ffunction-sections -fdata-sections)',
             'add_compile_options("-ffile-prefix-map='+build.as_posix()+'=." "-ffile-prefix-map='+ROOT.as_posix()+'=.")',
             'add_subdirectory("'+cfits.as_posix()+'" cfitsio-build)',
             'include_directories('+" ".join('"'+p.as_posix()+'"' for p in [astro / "include", astro / "include/astrometry", astro / "util", astro / "catalogs", gsl, cfits])+')']
    for name, paths in libraries.items():
        lines += ['add_library('+name+' STATIC '+" ".join('"'+p.as_posix()+'"' for p in paths)+')']
    lines += ['add_library(stellar_solver SHARED "'+bridge.as_posix()+'")',
              'target_compile_definitions(stellar_solver PRIVATE ANDROID ASTRO_BRIDGE_SRC_SHA256="'+bridge_hash+'")',
              'target_link_options(stellar_solver PRIVATE -Wl,--allow-multiple-definition -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--no-undefined)',
              'target_link_libraries(stellar_solver PRIVATE -Wl,--start-group '+" ".join(libraries)+' cfitsio -Wl,--end-group log m dl z)']
    (build / "CMakeLists.txt").write_text("\n".join(lines)+"\n", encoding="utf-8")
    binary = build / "out"
    cmake = args.sdk / "cmake/3.22.1/bin" / ("cmake.exe" if os.name == "nt" else "cmake")
    ninja = cmake.with_name("ninja.exe" if os.name == "nt" else "ninja")
    ndk = args.sdk / "ndk/26.3.11579264"
    run([cmake, "-G", "Ninja", "-S", build, "-B", binary, "-DCMAKE_TOOLCHAIN_FILE="+str(ndk / "build/cmake/android.toolchain.cmake"), "-DANDROID_ABI="+args.abi, "-DANDROID_PLATFORM=android-26", "-DCMAKE_MAKE_PROGRAM="+str(ninja)])
    command = [str(cmake), "--build", str(binary), "--target", "stellar_solver", "--parallel", "8"]
    with (build / "build.log").open("w", encoding="utf-8") as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        lines = (build / "build.log").read_text(encoding="utf-8", errors="replace").splitlines()
        print("\n".join(line for line in lines if "error:" in line)[:5000])
        print("Full native build log:", build / "build.log")
        raise subprocess.CalledProcessError(result.returncode, command)
    output = binary / "libstellar_solver.so"
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    strip = ndk / "toolchains/llvm/prebuilt" / host / "bin" / ("llvm-strip.exe" if os.name == "nt" else "llvm-strip")
    run([strip, "--strip-unneeded", output])
    data = output.read_bytes()
    assert data[:6] == b"\x7fELF\x02\x01"
    assert struct.unpack_from("<H", data, 18)[0] == (183 if args.abi == "arm64-v8a" else 62)
    phoff = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    alignments = [struct.unpack_from("<Q", data, phoff+i*size+48)[0] for i in range(count) if struct.unpack_from("<I", data, phoff+i*size)[0] == 1]
    assert alignments and all(a >= 16384 for a in alignments)
    assert ("STARCAM_BRIDGE_SRC_SHA256:"+bridge_hash).encode() in data
    exports = sorted(set(re.findall(r"Java_com_starcam_astro_astro_StellarSolverNative_\w+", bridge.read_text(encoding="utf-8"))))
    assert len(exports) == 6 and all(name.encode() in data for name in exports)
    report = dict(abi=args.abi, bridgeSha256=bridge_hash, sources={name: sha for name, _, sha in ARCHIVES}, patches=["atomic-native-quit-flag", "cfitsio347-format-string", "omit-unused-openngc-cli-module"], output=str(output), sha256=hashlib.sha256(output.read_bytes()).hexdigest())
    (build / "build-record.json").write_text(json.dumps(report, indent=2)+"\n")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(output, args.output)
        args.output.with_name(args.output.name+".src_sha256").write_text(bridge_hash+"\n", encoding="utf-8")
        print("Installed verified library:", args.output.resolve())
    print(json.dumps(report, indent=2))


if __name__ == "__main__": main()
