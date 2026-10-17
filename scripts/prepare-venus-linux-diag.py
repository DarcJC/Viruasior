#!/usr/bin/env python3
"""Package fixed Manjaro binaries/dependencies for an app-private, rootless diagnostic."""
import argparse
import json
import pathlib
import re
import subprocess
import tarfile

repo=pathlib.Path(__file__).resolve().parents[1]
root=pathlib.Path('/tmp/viruaisor-manjaro/root').resolve()
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--zink25',action='store_true',help='Use the separately built private Mesa 25 Zink comparison')
args=parser.parse_args()
zink=pathlib.Path('/tmp/viruaisor-manjaro/zink25-build/staged/opt/viruaisor/zink25').resolve()
allowed=(root,zink) if args.zink25 else (root,)
library_dirs=[root/'usr/lib']
stem='venus-linux-diag-zink25' if args.zink25 else 'venus-linux-diag'
out=repo/'.pi/device-debug/manjaro-plasma'
files={}
queue=[('bin/vulkaninfo',root/'usr/bin/vulkaninfo'),
       # vulkaninfo loads the loader dynamically; DT_NEEDED does not include it.
       ('lib/libvulkan.so.1',root/'usr/lib/libvulkan.so.1'),
       ('lib/libvulkan_virtio.so',root/'opt/viruaisor/venus/libvulkan_virtio.so'),
       ('bin/python3.11',root/'usr/bin/python3.11'),
       ('lib/libEGL.so.1',root/'usr/lib/libEGL.so.1'),
       ('lib/libEGL_mesa.so.0',root/'usr/lib/libEGL_mesa.so.0'),
       ('lib/libGLESv2.so.2',root/'usr/lib/libGLESv2.so.2'),
       ('lib/dri/zink_dri.so',root/'usr/lib/dri/zink_dri.so'),
       # Mesa's DRM-less entry point uses this name for the same megadriver.
       # The diagnostic explicitly selects GALLIUM_DRIVER=zink and checks Mali pixels.
       ('lib/dri/swrast_dri.so',root/'usr/lib/dri/swrast_dri.so'),
       ('lib/ld-linux-aarch64.so.1',root/'usr/lib/ld-linux-aarch64.so.1')]
if args.zink25:
    library_dirs.insert(0,zink/'lib')
    replacements={
        'lib/libEGL_mesa.so.0':zink/'lib/libEGL_mesa.so.0',
        'lib/dri/zink_dri.so':zink/'lib/dri/zink_dri.so',
        'lib/dri/swrast_dri.so':zink/'lib/dri/zink_dri.so',
    }
    queue=[(name,replacements.get(name,path)) for name,path in queue]
    # Mesa 25's DRI entry-point stub opens the megadriver dynamically.
    queue.append(('lib/libgallium-25.0.7.so',zink/'lib/libgallium-25.0.7.so'))
stdlib=root/'usr/lib/python3.11'
for path in stdlib.rglob('*'):
    if 'site-packages' in path.parts or '__pycache__' in path.parts or not path.is_file():
        continue
    name='lib/python3.11/'+str(path.relative_to(stdlib))
    if path.suffix == '.so':
        if path.name.startswith(('_ctypes.', '_struct.')):
            queue.append((name,path))
    else:
        source=path.resolve(strict=True)
        if not source.is_relative_to(root):
            raise RuntimeError('Python file leaves the prepared guest: '+str(path))
        files[name]=source
while queue:
    name,path=queue.pop()
    if name in files:
        continue
    source=path.resolve(strict=True)
    if not any(source.is_relative_to(base) for base in allowed) or not source.is_file():
        raise RuntimeError('Dependency leaves the prepared guest: '+str(path))
    files[name]=source
    dynamic=subprocess.run(['aarch64-linux-gnu-readelf','-d',str(source)],check=True,capture_output=True,text=True).stdout
    for needed in re.findall(r'\(NEEDED\).*\[([^\]]+)\]',dynamic):
        if '/' in needed:
            raise RuntimeError('Unexpected dependency path: '+needed)
        candidate=next((base/needed for base in library_dirs if (base/needed).is_file()),None)
        if candidate is None:
            raise RuntimeError('Missing guest dependency: '+needed)
        queue.append(('lib/'+needed,candidate))
with tarfile.open(out/(stem+'.tar.gz'),'w:gz',dereference=True) as archive:
    for name,path in sorted(files.items()):
        archive.add(path,arcname=name,recursive=False)
    for script in ('guest-gpu-draw-probe.py','venus-vtest-draw-probe.py'):
        archive.add(repo/'scripts'/script,arcname=script,recursive=False)
(out/(stem+'-files.json')).write_text(json.dumps({name:
    ('guest/'+str(path.relative_to(root)) if path.is_relative_to(root)
     else 'zink25/'+str(path.relative_to(zink)))
    for name,path in sorted(files.items())},indent=2)+'\n')
print('Prepared',len(files),'fixed guest files; no system files are installed')
