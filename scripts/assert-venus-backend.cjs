// Instrumentation completion is not renderer initialization or GPU execution.
function inspectVenusBackend(text) {
    const launches = text.split('\n').filter(line => /virtmgr.*Running crosvm with args:/.test(line));
    if (launches.length !== 1) return {status: 'inconclusive', reason: 'Expected exactly one isolated crosvm launch'};
    const launch = launches[0];
    if (!/backend=virglrenderer(?:,|")/.test(launch) || !/context-types=venus(?:,|")/.test(launch) || !/vulkan=true(?:,|")/.test(launch)) {
        return {status: 'inconclusive', reason: 'The actual launch does not select the Venus host renderer'};
    }
    const pid = text.match(/^.*?\s(\d+)\s+\d+\s+E crosvm\s*:.*Failed to create virtio gpu worker thread: invalid rutabaga build parameters\s*$/m)?.[1];
    if (pid && new RegExp('crosvm\\(' + pid + '\\) exited with status signal: 6 \\(SIGABRT\\)').test(text)) {
        return {status: 'blocked-host-renderer', reason: 'Venus selection reaches an unsupported rutabaga build and crosvm aborts', pid: Number(pid)};
    }
    return {status: 'inconclusive', reason: 'No compiled-backend rejection found; initialization and GPU execution still need verification'};
}
module.exports = {inspectVenusBackend};
