#!/usr/bin/env python3
"""
Describe the machine this runs on and, with --write, record it in MEASUREMENT_MACHINE.json at the
repository root. Running it with --write on a machine and pushing the file is how that machine is put
forward as the measurement machine: the campaign runner (tools/campaign.py) refuses to record timings
on a machine whose host name, Java runtime or Java vendor differs from the file.

    python3 tools/machine_info.py              # print the description
    python3 tools/machine_info.py --write      # print it and write MEASUREMENT_MACHINE.json
    python3 tools/machine_info.py --self-test  # check the parsers on fixed inputs

Standard library only, on Windows, macOS and Linux. A field that cannot be read is null and its reason
is listed under "errors", never guessed.
"""
import argparse
import datetime
import json
import os
import platform
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "MEASUREMENT_MACHINE.json"
# Fields a measurement must match exactly (the Java runtime is locked to the exact build and vendor).
LOCKED = ("java_runtime_version", "java_vendor", "python")


def _run(cmd, timeout=60):
    return subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=timeout)


def parse_java_properties(text):
    """Properties printed by `java -XshowSettings:properties -version` (on stderr), as a dict."""
    props, key = {}, None
    for line in text.splitlines():
        if " = " in line and line.startswith("    ") and not line.startswith("        "):
            key, _, value = line.strip().partition(" = ")
            props[key] = value
        elif key and line.startswith("        "):
            props[key] += "," + line.strip()                     # continuation of a list-valued property
        else:
            key = None
    return props


def parse_proc_cpuinfo(text):
    """CPU model name and number of physical cores from /proc/cpuinfo."""
    name, cores, cur = None, set(), {}
    for line in text.splitlines() + [""]:
        if not line.strip():
            if "physical id" in cur and "core id" in cur:
                cores.add((cur["physical id"], cur["core id"]))
            cur = {}
            continue
        key, _, value = line.partition(":")
        key, value = key.strip(), value.strip()
        cur[key] = value
        if key == "model name" and name is None:
            name = value
    return name, (len(cores) or None)


def _cpu_and_ram(errors):
    out = {"cpu": None, "cpu_physical_cores": None, "cpu_logical_threads": os.cpu_count(), "ram_gb": None}
    system = platform.system()
    try:
        if system == "Windows":
            import ctypes
            import winreg
            key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
            out["cpu"] = winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()

            class MemoryStatus(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
            status = MemoryStatus()
            status.dwLength = ctypes.sizeof(MemoryStatus)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
                out["ram_gb"] = round(status.ullTotalPhys / 2**30, 1)
            cores = _run(["powershell", "-NoProfile", "-Command",
                          "(Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum"]).stdout
            out["cpu_physical_cores"] = int(cores.strip())
        elif system == "Darwin":
            out["cpu"] = _run(["sysctl", "-n", "machdep.cpu.brand_string"]).stdout.strip()
            out["cpu_physical_cores"] = int(_run(["sysctl", "-n", "hw.physicalcpu"]).stdout)
            out["ram_gb"] = round(int(_run(["sysctl", "-n", "hw.memsize"]).stdout) / 2**30, 1)
        else:
            out["cpu"], out["cpu_physical_cores"] = parse_proc_cpuinfo(Path("/proc/cpuinfo").read_text())
            for line in Path("/proc/meminfo").read_text().splitlines():
                if line.startswith("MemTotal:"):
                    out["ram_gb"] = round(int(line.split()[1]) / 2**20, 1)
    except Exception as e:                                                     # recorded, not hidden
        errors.append("cpu/ram: %s: %s" % (type(e).__name__, e))
    return out


def java_env(errors, java="java"):
    """The Java runtime that `java` on PATH starts: exact runtime version, vendor, VM name and home."""
    env = {"java_runtime_version": None, "java_vendor": None, "java_vm_name": None, "java_home": None}
    try:
        p = _run([java, "-XshowSettings:properties", "-version"])
        props = parse_java_properties(p.stderr + p.stdout)
        env["java_runtime_version"] = props.get("java.runtime.version")
        env["java_vendor"] = props.get("java.vendor")
        env["java_vm_name"] = props.get("java.vm.name")
        env["java_home"] = props.get("java.home")
        if env["java_runtime_version"] is None:
            errors.append("java: java.runtime.version not printed")
    except Exception as e:
        errors.append("java: %s: %s" % (type(e).__name__, e))
    return env


def current_env(errors=None):
    """The locked fields as this machine has them."""
    errors = [] if errors is None else errors
    j = java_env(errors)
    return {"java_runtime_version": j["java_runtime_version"], "java_vendor": j["java_vendor"],
            "python": platform.python_version()}, j


def describe():
    errors = []
    locked, j = current_env(errors)
    win = platform.system() == "Windows"
    return {
        "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "hostname": platform.node(),
        "os": {"system": platform.system(), "release": platform.release(), "version": platform.version(),
               "windows_edition": platform.win32_edition() if win else None},
        "architecture": platform.machine(),
        **_cpu_and_ram(errors),
        "java_vm_name": j["java_vm_name"],
        "java_home_name": Path(j["java_home"]).name if j["java_home"] else None,   # the folder name only, not the path
        "locked": locked,
        "python_implementation": platform.python_implementation(),
        "errors": errors,
    }


def differences(recorded, desc):
    """Fields in which this machine differs from a recorded description (host name and locked fields)."""
    out = []
    if recorded.get("hostname") != desc.get("hostname"):
        out.append("hostname: recorded %r, here %r" % (recorded.get("hostname"), desc.get("hostname")))
    for k in LOCKED:
        a, b = recorded.get("locked", {}).get(k), desc["locked"].get(k)
        if a != b:
            out.append("%s: recorded %r, here %r" % (k, a, b))
    return out


def write(desc, path=OUT, replace=False):
    """Write the description; refuse to replace one of another machine or environment unless asked."""
    if path.exists() and not replace:
        diff = differences(json.loads(path.read_text(encoding="utf-8")), desc)
        if diff:
            raise SystemExit("%s describes another machine or environment (%s); pass --replace only if this "
                             "machine is to replace it" % (path.as_posix(), "; ".join(diff)))
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(desc, indent=2) + "\n")
    return path.as_posix()


def self_test():
    sample = ("Property settings:\n    file.encoding = UTF-8\n    java.class.path = \n"
              "    java.runtime.version = 25.0.1+8-LTS\n    java.vendor = Eclipse Adoptium\n"
              "    java.vm.name = OpenJDK 64-Bit Server VM\n    sun.boot.library.path = /a\n        /b\n"
              "\nopenjdk version \"25.0.1\" 2025-10-21 LTS\n")
    p = parse_java_properties(sample)
    checks = [
        (p.get("java.runtime.version") == "25.0.1+8-LTS", "runtime version parsed"),
        (p.get("java.vendor") == "Eclipse Adoptium", "vendor parsed"),
        (p.get("sun.boot.library.path") == "/a,/b", "list continuation joined"),
        ("openjdk version" not in "".join(p), "version banner is not a property"),
    ]
    cpu = "processor\t: 0\nphysical id\t: 0\ncore id\t: 0\nmodel name\t: X\n\nprocessor\t: 1\nphysical id\t: 0\ncore id\t: 0\n\n" \
          "processor\t: 2\nphysical id\t: 0\ncore id\t: 1\n\n"
    checks.append((parse_proc_cpuinfo(cpu) == ("X", 2), "hyper-threads counted once per physical core"))
    rec = {"hostname": "A", "locked": {"java_runtime_version": "1", "java_vendor": "V", "python": "3"}}
    here = {"hostname": "A", "locked": {"java_runtime_version": "1", "java_vendor": "V", "python": "3"}}
    checks.append((differences(rec, here) == [], "identical machine: no difference"))
    here2 = {"hostname": "B", "locked": {"java_runtime_version": "2", "java_vendor": "V", "python": "3"}}
    checks.append((len(differences(rec, here2)) == 2, "other host and other runtime: both reported"))
    bad = [msg for ok, msg in checks if not ok]
    print("machine_info self-test: %d/%d checks passed%s" % (len(checks) - len(bad), len(checks),
                                                             "" if not bad else "; FAILED: " + ", ".join(bad)))
    return 1 if bad else 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help="write %s at the repository root" % OUT.name)
    ap.add_argument("--replace", action="store_true", help="replace a description of another machine")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    desc = describe()
    print(json.dumps(desc, indent=2))
    if desc["errors"]:
        print("could not read: " + "; ".join(desc["errors"]), file=sys.stderr)
    if args.write:
        print("-> " + write(desc, replace=args.replace))
    return 0


if __name__ == "__main__":
    sys.exit(main())
