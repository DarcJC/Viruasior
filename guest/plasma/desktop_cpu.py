#!/usr/bin/python3
"""Keep display services on large cores without preferring power-hungry prime cores."""
import os
from pathlib import Path
import sys


def desktop_cpus(allowed, capacities):
    if len(allowed) < 2 or any(capacities.get(cpu, 0) <= 0 for cpu in allowed):
        return set(allowed)
    # Use the whole topology to recognize little/large/prime tiers even if the
    # inherited mask excludes little cores. Two-tier big.LITTLE has no separate
    # prime tier. Never widen the inherited mask or reduce it to a single CPU.
    tiers = sorted({capacity for capacity in capacities.values() if capacity > 0})
    target = tiers[-2] if len(tiers) >= 3 else tiers[-1]
    selected = {cpu for cpu in allowed if capacities[cpu] == target}
    return selected if len(selected) >= 2 else set(allowed)


def main():
    programs = {'desktop-xorg': '/usr/bin/Xorg', 'desktop-kwin': '/usr/bin/kwin_x11'}
    program = programs.get(Path(sys.argv[0]).name)
    if program is None:
        raise SystemExit('Invoke as desktop-xorg or desktop-kwin')
    try:
        allowed = os.sched_getaffinity(0)
        capacities = {int(path.parent.name[3:]): int(path.read_text())
                      for path in Path('/sys/devices/system/cpu').glob('cpu[0-9]*/cpu_capacity')}
        selected = desktop_cpus(allowed, capacities)
        if selected != allowed:
            os.sched_setaffinity(0, selected)
        print(f'VIRUAISOR_DESKTOP_CPU program={Path(program).name} cpus=' +
              ','.join(map(str, sorted(selected))), flush=True)
    except (OSError, ValueError) as error:
        # Affinity is an optimization; missing topology must not prevent boot.
        print(f'VIRUAISOR_DESKTOP_CPU unchanged reason={type(error).__name__}', flush=True)
    # exec preserves this PID. Venus checks it together with the Zink engine
    # name, so clients spawned by the compositor retain the compatibility wait.
    os.environ['VIRUAISOR_DESKTOP_ASYNC_SYNC_FD_PID'] = str(os.getpid())
    os.execv(program, [program, *sys.argv[1:]])


if __name__ == '__main__':
    main()
