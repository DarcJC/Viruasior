#!/usr/bin/python3
"""Keep the two display services on the fastest guest CPU tier."""
import os
from pathlib import Path
import sys


def fastest_cpus(allowed, capacities):
    if len(allowed) < 2 or any(capacities.get(cpu, 0) <= 0 for cpu in allowed):
        return set(allowed)
    # Include at least two CPUs and every CPU in the same capacity tier. On
    # homogeneous guests this preserves the entire original affinity mask.
    threshold = sorted((capacities[cpu] for cpu in allowed), reverse=True)[1]
    return {cpu for cpu in allowed if capacities[cpu] >= threshold}


def main():
    programs = {'desktop-xorg': '/usr/bin/Xorg', 'desktop-kwin': '/usr/bin/kwin_x11'}
    program = programs.get(Path(sys.argv[0]).name)
    if program is None:
        raise SystemExit('Invoke as desktop-xorg or desktop-kwin')
    try:
        allowed = os.sched_getaffinity(0)
        capacities = {cpu: int(Path(f'/sys/devices/system/cpu/cpu{cpu}/cpu_capacity').read_text())
                      for cpu in allowed}
        selected = fastest_cpus(allowed, capacities)
        if selected != allowed:
            os.sched_setaffinity(0, selected)
        print(f'VIRUAISOR_DESKTOP_CPU program={Path(program).name} cpus=' +
              ','.join(map(str, sorted(selected))), flush=True)
    except (OSError, ValueError) as error:
        # Affinity is an optimization; missing topology must not prevent boot.
        print(f'VIRUAISOR_DESKTOP_CPU unchanged reason={type(error).__name__}', flush=True)
    os.execv(program, [program, *sys.argv[1:]])


if __name__ == '__main__':
    main()
