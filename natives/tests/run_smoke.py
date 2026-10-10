#!/usr/bin/env python3
"""Compile JNI-only Java test stubs, verify production descriptors, run a real native.

No Gradle, Minecraft startup, network, or replacement of installed mods is involved.
The only generated files live below the explicitly supplied --output directory.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
RAPIER = "dev.ryanhcode.sable.physics.impl.rapier.Rapier3D"
CALLBACK = "dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback"
EXTENSION = "ipl.sable.natives.IplRapierNatives"


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / (name + (".exe" if os.name == "nt" else "")) if home else None
    if candidate and candidate.is_file():
        return str(candidate)
    result = shutil.which(name)
    if not result:
        raise RuntimeError(f"{name} is required (Java 21)")
    return result


def run(command, cwd, timeout=60):
    return subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout, check=True).stdout


def locate_classes(jar, output):
    targets = {name.replace(".", "/") + ".class": name for name in (RAPIER, CALLBACK)}
    found = {}

    def walk(blob, provenance, depth=0):
        if depth > 4:
            raise RuntimeError("Nested JAR depth exceeded")
        with zipfile.ZipFile(io.BytesIO(blob)) as archive:
            names = set(archive.namelist())
            relevant = names.intersection(targets)
            if relevant:
                dest = output / f"reference-{len(found)}.jar"
                dest.write_bytes(blob)
                for entry in relevant:
                    name = targets[entry]
                    if name in found:
                        raise RuntimeError(f"Ambiguous production class {name}")
                    found[name] = {"path": dest, "provenance": provenance,
                                   "class_sha256": hashlib.sha256(archive.read(entry)).hexdigest()}
            for entry in sorted(names):
                if entry.endswith(".jar"):
                    walk(archive.read(entry), provenance + "!/" + entry, depth + 1)

    walk(jar.read_bytes(), jar.name)
    if len(found) != len(targets):
        raise RuntimeError(f"Missing production classes: {set(targets.values()) - set(found)}")
    return found


def descriptors(output, native_only=False):
    result = {}
    method = None
    for line in output.splitlines():
        match = re.search(r"\b([\w$]+)\([^;]*\);$", line.strip())
        if match:
            method = match.group(1) if not native_only or " native " in line else None
        elif line.strip().startswith("descriptor:") and method:
            result[method] = line.strip().split(":", 1)[1].strip()
            method = None
    return result


def source_native_descriptors(source):
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    types = {"void": "V", "boolean": "Z", "int": "I", "long": "J", "double": "D"}
    def encoded(value):
        return "[" + encoded(value[:-2]) if value.endswith("[]") else types[value]
    return {name: "(" + "".join(encoded(arg.strip().split()[0]) for arg in args.split(",") if arg.strip())
                  + ")" + encoded(return_type)
            for return_type, name, args in re.findall(r"public\s+static\s+native\s+(\S+)\s+(\w+)\s*\((.*?)\)\s*;", source, re.S)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native", required=True, type=Path)
    parser.add_argument("--sable-jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--compile-only", action="store_true")
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.native = args.native.resolve()
    args.sable_jar = args.sable_jar.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    classes = args.output / "classes"
    classes.mkdir(exist_ok=True)
    report = {"status": "failed", "harness": "exact-descriptor JNI test stubs; actual Rust native",
              "native": str(args.native), "sable_jar_sha256": digest(args.sable_jar),
              "limitations": ["No Minecraft/NeoForge lifecycle or mixin startup exercised",
                              "No Java fused-step scheduler or real server assembly/staff input exercised",
                              "Collision callback Java game adapter is replaced by a descriptor-matched test implementation",
                              "Three charts tested; arbitrary chart count/rotated/scaled portal behavior is not covered"]}
    try:
        java, javac, javap = (java_tool(name) for name in ("java", "javac", "javap"))
        report["java_version"] = run([java, "-version"], args.output).strip()
        sources = sorted(str(path) for path in (HERE / "java").rglob("*.java"))
        run([javac, "--release", "21", "-d", str(classes), *sources], args.output)
        references = locate_classes(args.sable_jar, args.output)
        compared = {}
        for name in (RAPIER, CALLBACK):
            production = descriptors(run([javap, "-classpath", str(references[name]["path"]), "-p", "-s", name], args.output))
            stub = descriptors(run([javap, "-classpath", str(classes), "-p", "-s", name], args.output), native_only=name == RAPIER)
            if name == CALLBACK:
                stub = {"onCollision": stub["onCollision"]}
            for method, descriptor in stub.items():
                if production.get(method) != descriptor:
                    raise AssertionError(f"{name}.{method}: stub {descriptor} != production {production.get(method)}")
            compared[name] = {"provenance": references[name]["provenance"],
                              "class_sha256": references[name]["class_sha256"], "methods": stub}
        extension_source = ROOT / "src/main/java/ipl/sable/natives/IplRapierNatives.java"
        production = source_native_descriptors(extension_source.read_text(encoding="utf-8"))
        stub = descriptors(run([javap, "-classpath", str(classes), "-p", "-s", EXTENSION], args.output), native_only=True)
        for method, descriptor in stub.items():
            if production.get(method) != descriptor:
                raise AssertionError(f"{EXTENSION}.{method} descriptor mismatch")
        compared[EXTENSION] = {"production_source_sha256": digest(extension_source), "methods": stub}
        report["verified_descriptors"] = compared
        if args.compile_only:
            report["status"] = "compiled-and-descriptors-verified; native-not-run"
        else:
            report["native_sha256"] = digest(args.native)
            command = [java, "-Xcheck:jni", "-cp", str(classes), "NativeSmoke", str(args.native)]
            process = subprocess.run(command, cwd=args.output, text=True, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, timeout=120)
            (args.output / "native-smoke.log").write_text(process.stdout, encoding="utf-8")
            report["exit_code"] = process.returncode
            report["log_sha256"] = digest(args.output / "native-smoke.log")
            if process.returncode != 0 or "NATIVE_SMOKE_PASS " not in process.stdout:
                raise RuntimeError("Native smoke failed; inspect native-smoke.log")
            report["verified_cases"] = [line for line in process.stdout.splitlines() if line.startswith(("CASE ", "NATIVE_SMOKE_PASS "))]
            report["status"] = "passed"
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        (args.output / "native-smoke-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"status": report["status"], "report": str(args.output / "native-smoke-report.json")}))


if __name__ == "__main__":
    main()
