#!/usr/bin/env node
// Native comparison gate only. Enumeration is NOT a GPU-workload or presentation pass.
const fs = require('node:fs');

function inspectNativeEnumeration(text) {
    const lines = text.split(/\r?\n/).map(line => line.trim());
    const count = value => lines.filter(line => line === value).length;
    if (count('DIAG_NATIVE_COMPARE_BEGIN') !== 1 || count('DIAG_NATIVE_COMPARE_END') !== 1) {
        return {ok:false, reason:'Expected one native comparison with complete markers'};
    }
    const begin = lines.indexOf('DIAG_NATIVE_COMPARE_BEGIN');
    const end = lines.indexOf('DIAG_NATIVE_COMPARE_END');
    if (end <= begin) return {ok:false, reason:'Native comparison markers are out of order'};
    const body = lines.slice(begin+1,end);
    const results = [];
    for (const [label,exit] of [['NATIVE_FULL_GROUPS','NATIVE_FULL_EXIT'],['NATIVE_RENDER_ONLY','NATIVE_RENDER_EXIT']]) {
        const starts = body.map((line,i) => line === label ? i : -1).filter(i => i >= 0);
        const exits = body.map((line,i) => new RegExp('^'+exit+'=\\d+$').test(line) ? i : -1).filter(i => i >= 0);
        if (starts.length !== 1 || exits.length !== 1 || exits[0] <= starts[0]) {
            return {ok:false, reason:'Missing or ambiguous native result: '+label};
        }
        const status = Number(body[exits[0]].split('=')[1]);
        if (status !== 0) return {ok:false, reason:exit+'='+status+'; collector success is not GPU success'};
        const output = body.slice(starts[0]+1,exits[0]).join('\n');
        if (/Segmentation fault|llvmpipe|lavapipe|softpipe|swiftshader|PHYSICAL_DEVICE_TYPE_CPU/i.test(output)) {
            return {ok:false, reason:label+' reports a fault or software device'};
        }
        if (!/^uid=1000\(droid\) gid=/m.test(output) || !/^RENDER_NODE_READ_WRITE$/m.test(output)) {
            return {ok:false, reason:label+' did not verify non-root identity and render-node access'};
        }
        if ((output.match(/^GPU\d+:$/gm) || []).length !== 1 || !/^GPU0:$/m.test(output) ||
            !/^deviceName\s*=\s*Mali[^\n]*$/m.test(output) ||
            !/^vendorID\s*=\s*0x13b5$/im.test(output) ||
            !/^driverID\s*=\s*DRIVER_ID_ARM_PROPRIETARY$/m.test(output) ||
            !/^deviceType\s*=\s*PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU$/m.test(output)) {
            return {ok:false, reason:label+' did not enumerate the expected single Mali/ARM device'};
        }
        results.push(label);
    }
    return {ok:true, scope:'enumeration_only', workloadProven:false, presentationProven:false, results};
}

module.exports = {inspectNativeEnumeration};
if (require.main === module) {
    if (process.argv.length !== 3) {
        console.error('Usage: node scripts/assert-guest-vulkan-enumeration.cjs <guest-native-vulkan-comparison.txt>');
        process.exitCode=2;
    } else {
        const result=inspectNativeEnumeration(fs.readFileSync(process.argv[2],'utf8'));
        if (!result.ok) {
            console.error('RED: '+result.reason);
            process.exitCode=1;
        } else {
            console.log('PASS: both non-root native group controls enumerate the expected Mali/ARM device');
            console.log('NOT YET PROVEN: GPU commands, shaders, Weston/KMS or Android frames');
        }
    }
}
