#!/usr/bin/env python3
"""Drive the real app/VM without a display; store bounded, non-secret evidence."""
import argparse
import importlib
import json
import pathlib
import re
import subprocess
import sys
import time
import threading

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
Device = importlib.import_module("update-guest-cjk").Device
REPO = pathlib.Path(__file__).resolve().parents[1]


def wait(condition, seconds=90):
    end = time.monotonic()+seconds
    while time.monotonic() < end:
        result = condition()
        if result:
            return result
        time.sleep(0.5)
    raise RuntimeError("Timed out waiting for headless VM evidence")


def main(args):
    device = Device(args.adb, args.package)
    uid = int(device.run(["id", "-u"]).strip())
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    folder = "files/headless-vpn-control"
    device.stopped()
    device.run(["mkdir", "-p", folder])
    device.run(["rm", "-f", folder+"/ready", folder+"/ack.json"])
    device.write(folder+"/request.json", b'{"sequence":0,"operation":"idle"}')
    sequence = 0
    def root(command):
        return device.host(["shell", "su", "-c", command]).decode(errors="replace")
    def asleep():
        power = device.host(["shell", "dumpsys", "power"]).decode()
        assert "mWakefulness=Asleep" in power or "mWakefulness=Dozing" in power, "Display woke unexpectedly"
    def running():
        return "crosvm_default" in device.host(["shell", "ps", "-A"]).decode()
    def read(path):
        try:
            return device.read(path).decode()
        except RuntimeError:
            return ""
    def send(operation):
        nonlocal sequence
        sequence += 1
        device.write(folder+"/request.tmp", json.dumps(dict(sequence=sequence, operation=operation)).encode())
        device.run(["mv", "-f", folder+"/request.tmp", folder+"/request.json"])
        wait(lambda: f'"sequence":{sequence}' in read(folder+"/ack.json").replace(" ", ""), 15)
    def guest(command, attempts=1, expect_ok=True, suffix=""):
        for attempt in range(attempts):
            path = "files/guest-check-"+command+".json"
            device.run(["rm", "-f", path])
            send(command)
            report = json.loads(wait(lambda: read(path), 80))
            (output/(args.mode+"-"+command+suffix+".json")).write_text(json.dumps(report, indent=2)+"\n")
            if bool(report.get("ok")) == expect_ok:
                print(command+" "+json.dumps(report), flush=True)
                return report
            if attempt+1 < attempts:
                time.sleep(3)
        raise RuntimeError(command+" "+json.dumps(report))

    log = (output/(args.mode+"-driver.log")).open("wb")
    thaw_stop = threading.Event()
    def thaw_for_test():
        while not thaw_stop.is_set():
            # Xiaomi's Greeze ignores the normal foreground-service/wakelock.
            # Thaw only this verified app UID; no global disable/whitelist/file.
            root(f"cmd greezer thuid {uid} 1000 viruaisor-test")
            thaw_stop.wait(2)
    thaw = threading.Thread(target=thaw_for_test, daemon=True) if args.vendor_thaw else None
    if thaw:
        thaw.start()
    app_died = False
    driver = subprocess.Popen([args.adb, "shell", "am", "instrument", "-w", "-e", "class",
        "pro.darc.viruaisor.VmVpnHeadlessTest#driveFromHost", "-e", "headlessController", "20261020",
        args.package+".test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
    try:
        wait(lambda: read(folder+"/ready"), 30)
        send(args.mode)
        send("start")
        wait(running, 40)
        print("VM_STARTED mode="+args.mode, flush=True)
        asleep()
        if args.mode == "vpn":
            wait(lambda: '"vpn"' in read("files/vpn-lease/journal"), 45)
            journal = json.loads(read("files/vpn-lease/journal"))
            tunnel = journal["vpn"]["name"]
            assert re.fullmatch(r"(?:tun|ipsec)[0-9]{1,5}", tunnel)
            table = journal["vpn"]["table"]
            assert isinstance(table, int) and 1001 <= table <= 1000000
            filters = root("tc filter show dev avf_tap_fixed ingress")
            assert "bpf" in filters and "direct-action" in filters and "pref 1" in filters, filters
            (output/"tc-vpn.txt").write_text(filters)
            (output/"rules-vpn.txt").write_text(root("ip rule show"))
            print("VPN_RULES_AND_INGRESS_VERIFIED interface="+tunnel, flush=True)
        else:
            filters = root("tc filter show dev avf_tap_fixed ingress")
            (output/"tc-ordinary.txt").write_text(filters)
            (output/"tc-pref-2.txt").write_text(root("tc filter show dev avf_tap_fixed ingress pref 2"))
        time.sleep(5)
        guest("FONT_STATUS", 3)
        guest("NETWORK_STATUS", 2)
        if args.benchmark:
            names = device.host(["shell", "ps", "-A", "-o", "PID,NAME"]).decode().splitlines()
            pids = {name: int(pid) for line in names[1:] for pid, name in [line.strip().split(maxsplit=1)]
                    if name in ("crosvm_default", args.package+":root:0")}
            hz = int(device.host(["shell", "getconf", "CLK_TCK"]).decode().strip())
            def ticks(pid):
                fields = root(f"cat /proc/{pid}/stat").rsplit(")", 1)[1].split()
                return int(fields[11])+int(fields[12])
            before = {name: ticks(pid) for name, pid in pids.items()}
            started = time.monotonic()
            guest("NETWORK_BENCH")
            duration = time.monotonic()-started
            cpu = {name: dict(cpuSeconds=round((ticks(pid)-before[name])/hz, 3), wallSeconds=round(duration, 3))
                   for name, pid in pids.items()}
            (output/(args.mode+"-cpu.json")).write_text(json.dumps(cpu, indent=2)+"\n")
        if args.render:
            guest("CHROMIUM_RENDER")
        if args.kill_switch:
            assert args.mode == "vpn"
            priority = 18000+uid%800*2
            selector = f"priority {priority} iif avf_tap_fixed lookup {table}"
            root("ip -4 rule del "+selector)
            try:
                guest("NETWORK_STATUS", expect_ok=False, suffix="-route-loss")
                v4 = root("ip -4 rule show")
                v6 = root("ip -6 rule show")
                assert f"{priority+1}:" in v4 and f"{priority+1}:" in v6
                assert "iif avf_tap_fixed unreachable" in v4 and "iif avf_tap_fixed unreachable" in v6
            finally:
                root("ip -4 rule add "+selector)
            guest("NETWORK_STATUS", 2, suffix="-restored")
            print("KILL_SWITCH_ROUTE_LOSS_AND_RECOVERY_VERIFIED ipv6=blocked", flush=True)
        if args.mode == "vpn":
            (output/"filter-counters.txt").write_text(root(f"iptables -nvx -L vvm_{uid}_f"))
            (output/"dns-counters.txt").write_text(root(f"iptables -t nat -nvx -L vvm_{uid}_d"))
        asleep()
        if args.app_death:
            device.host(["shell", "am", "force-stop", args.package])
            app_died = True
    finally:
        try:
            if not app_died:
                send("stop")
            wait(lambda: not running(), 50)
            wait(lambda: not read("files/vpn-lease/journal"), 30)
            if not app_died:
                send("vpn")
                send("finish")
            driver.wait(timeout=20)
            for family in ("-4", "-6"):
                active_rules = root(f"ip {family} rule show")
                assert not re.search(rf"(?m)^{18000+uid%800*2+1}:.*avf_tap_fixed", active_rules)
            for binary, tables in (("iptables", ("filter", "nat", "mangle")), ("ip6tables", ("filter",))):
                for table in tables:
                    assert f"vvm_{uid}_" not in root(f"{binary} -t {table} -S")
            asleep()
            print("VM_STOPPED scoped_lease_removed=1 screen=asleep", flush=True)
        except Exception:
            device.host(["shell", "am", "force-stop", args.package])
            raise
        finally:
            thaw_stop.set()
            if thaw:
                thaw.join(timeout=5)
            (output/"app.log").write_bytes(device.host(["logcat", "-d", "-s", "ViruaisorVpnRoot", "ViruaisorService", "ViruaisorAvf"]))
            log.close()
    assert driver.returncode == 0
    if not app_died:
        assert "OK (1 test)" in (output/(args.mode+"-driver.log")).read_text(), "Instrumentation did not complete"


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default="pro.darc.viruaisor")
    parser.add_argument("--output", type=pathlib.Path, default=REPO / ".pi/device-debug/vpn-cjk-validation-20261020")
    parser.add_argument("--mode", choices=("vpn", "ordinary"), default="vpn")
    parser.add_argument("--benchmark", action="store_true")
    parser.add_argument("--render", action="store_true")
    parser.add_argument("--vendor-thaw", action="store_true", help="Temporarily thaw only the test app UID on Xiaomi")
    parser.add_argument("--kill-switch", action="store_true", help="Remove and restore only this lease's VPN lookup rule")
    parser.add_argument("--app-death", action="store_true", help="Verify owned lease cleanup after force-stopping the app")
    main(parser.parse_args())
