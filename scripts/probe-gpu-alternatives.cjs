// Collect read-only host evidence and optionally run the isolated Venus instrumentation probe.
// Build :app:assembleDebugAndroidTest first. No rootfs, manifest, policy or GPU binding changes.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const {inspectVenusBackend} = require('./assert-venus-backend.cjs');
const {resolveApplicationId,parsePackageArgs}=require('./application-id.cjs');
const adb = process.env.ADB || 'adb';
const {args,packageName}=parsePackageArgs(process.argv.slice(2));
if (args.some(arg => !['--venus-backend', '--guest-inventory'].includes(arg))) {
    throw new Error('Usage: node scripts/probe-gpu-alternatives.cjs [--venus-backend] [--guest-inventory] [--package application.id]');
}
const applicationId=resolveApplicationId({packageName});
const out = path.resolve('.pi/device-debug/gpu-alternatives-' + new Date().toISOString().replace(/[:.]/g, '-'));
fs.mkdirSync(out, {recursive: true});
function run(args, timeout = 15_000) {
    const result = cp.spawnSync(adb, args, {encoding: 'utf8', timeout, maxBuffer: 16 * 1024 * 1024});
    return {exit: result.status, stdout: result.stdout || '', stderr: result.stderr || '', error: result.error};
}
function save(name, args, timeout) {
    const result = run(args, timeout);
    fs.writeFileSync(path.join(out, name), result.stdout + result.stderr + (result.error ? '\n' + result.error.message + '\n' : ''));
    if (result.error) throw result.error;
    return result;
}
const quote = value => "'" + value.replace(/'/g, "'\\''") + "'";
const shell = command => run(['shell', command]);
const policyCommand = 'getenforce; settings get global hidden_api_policy; settings get global hidden_api_blacklist_exemptions';
const policy = save('policy-before.txt', ['shell', policyCommand]).stdout;
if (!/^Enforcing\r?$/m.test(policy)) throw new Error('SELinux must be Enforcing');
save('host-driver.txt', ['shell', 'getprop ro.build.fingerprint; uname -r; ls -l /dev/dri /dev/mali* /dev/udmabuf 2>/dev/null; cat /proc/modules | grep -E "mali|panfrost|panthor|xring_drm"']);
save('host-gpu-binding.txt', ['shell', 'su -c ' + quote('id; cat /sys/class/misc/mali0/device/gpuinfo; cat /sys/class/misc/mali0/device/uevent; cat /sys/class/drm/card0/device/uevent; readlink /sys/class/drm/card0/device/driver; ls /sys/module/panfrost /sys/module/panthor 2>&1; readlink /sys/class/misc/mali0/device/iommu_group; ls -l /sys/class/misc/mali0/device/iommu_group/devices; ls -l /dev/vfio; ls -l /vendor/etc/avf/assignable_devices.xml 2>&1; getprop ro.boot.hypervisor.vm_dtbo_idx')]);
const vk = save('host-vulkan.json', ['shell', 'cmd gpu vkjson']);
const vkData = JSON.parse(vk.stdout);
const devices = vkData.devices || [];
const required = ['VK_EXT_external_memory_dma_buf', 'VK_EXT_image_drm_format_modifier', 'VK_EXT_queue_family_foreign'];
const summary = devices.map(device => ({
    name: device.properties?.deviceName,
    apiVersion: device.properties?.apiVersion,
    driverVersion: device.properties?.driverVersion,
    venusAndroidExtensions: Object.fromEntries(required.map(name => [name, (device.extensions || []).some(extension => extension.extensionName === name)])),
}));
fs.writeFileSync(path.join(out, 'host-vulkan-summary.json'), JSON.stringify(summary, null, 2) + '\n');
save('crosvm-help.txt', ['shell', 'su -c ' + quote('/apex/com.android.virt/bin/crosvm run --help')]);
if (args.includes('--guest-inventory')) {
    const push = save('guest-inventory-upload.txt', ['push', 'scripts/inspect-gpu-guest-offline.sh', '/data/local/tmp/viruaisor-inspect-gpu-guest-offline.sh']);
    if (push.exit !== 0) throw new Error('Guest inventory helper upload failed');
    const inventory = save('guest-offline-inventory.txt', ['shell', 'su -c ' + quote(`PACKAGE=${quote(applicationId)} sh /data/local/tmp/viruaisor-inspect-gpu-guest-offline.sh`)], 30_000);
    if (inventory.exit !== 0 || !inventory.stdout.includes('GUEST_OFFLINE_READ_ONLY_END')) throw new Error('Guest read-only inventory failed');
}
if (args.includes('--venus-backend')) {
    const processes = shell('ps -A').stdout;
    if (/crosvm/.test(processes)||processes.includes(`${applicationId}:root:`)) throw new Error('Stop the VM/display helper first');
    const testApplicationId=`${applicationId}.test`;
    const installed = shell(`pm path ${testApplicationId}`).stdout;
    if (!/^package:/m.test(installed)) throw new Error('Install the built androidTest APK and approve the device installer first');
    const start = shell("date '+%m-%d %H:%M:%S.000'").stdout.trim();
    const instrument = save('venus-instrumentation.txt', ['shell', 'am', 'instrument', '-w', '-r', '-e', 'gpuBackendProbe', 'venus', '-e', 'class', 'pro.darc.viruaisor.VenusBackendProbeTest', `${testApplicationId}/androidx.test.runner.AndroidJUnitRunner`], 60_000);
    const capture = save('venus-logcat.txt', ['logcat', '-d', '-T', start, '-b', 'all']);
    const verdict = inspectVenusBackend(capture.stdout);
    fs.writeFileSync(path.join(out, 'venus-verdict.json'), JSON.stringify(verdict, null, 2) + '\n');
    console.log('Venus host verdict: ' + JSON.stringify(verdict));
    console.log('Instrumentation completion is only evidence collection, not GPU acceleration.');
}
save('processes-after.txt', ['shell', 'ps -AZ']);
const after = save('policy-after.txt', ['shell', policyCommand]).stdout;
if (policy !== after) throw new Error('Policy snapshot changed');
console.log(JSON.stringify(summary, null, 2));
console.log('Evidence: ' + out);
