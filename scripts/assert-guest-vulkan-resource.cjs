#!/usr/bin/env node
// A buffer-command/readback check is not a shader or Android presentation check.
const fs=require('node:fs');
function inspectVulkanResource(text) {
    const lines=text.split(/\r?\n/).map(line=>line.trim());
    const starts=lines.flatMap((line,i)=>line==='DIAG_VK_RESOURCE_BEGIN'?[i]:[]);
    const ends=lines.flatMap((line,i)=>line==='DIAG_VK_RESOURCE_END'?[i]:[]);
    const fail=reason=>({ok:false,reason});
    if(starts.length!==1 || ends.length!==1 || ends[0]<=starts[0]) {
        return fail('Expected one complete Vulkan resource result');
    }
    const body=lines.slice(starts[0]+1,ends[0]);
    const exits=body.filter(line=>/^GUEST_VK_RESOURCE_EXIT=\d+$/.test(line));
    if(exits.length!==1 || exits[0]!=='GUEST_VK_RESOURCE_EXIT=0') {
        return fail('Missing, ambiguous or nonzero guest exit; collection alone is not a pass');
    }
    if(/Segmentation fault|VULKAN_RESOURCE_FAIL|VK_ERROR=|llvmpipe|lavapipe|softpipe|swiftshader/i.test(body.join('\n'))) {
        return fail('Guest resource probe reports a fault or software renderer');
    }
    const once=predicate=>body.filter(predicate).length===1;
    if(!once(line=>/^VK_UID=1000 VK_GID=\d+ VK_GROUPS=\[[\d, ]*\]$/.test(line)) ||
        !once(line=>line==='VULKAN_ABI_LAYOUT_PASS') ||
        !once(line=>/^VK_DEVICE=Mali[^\n]* VENDOR=0x13b5 TYPE=1$/.test(line))) {
        return fail('Expected verified UID 1000, native ABI and the intended non-CPU Mali device');
    }
    let previous=-1;
    for(const stage of ['vkCreateInstance','vkCreateDevice','vkAllocateMemory','vkMapMemory','vkQueueSubmit','vkQueueWaitIdle']) {
        const marker='VK_STAGE='+stage;
        if(!once(line=>line===marker) || body.indexOf(marker)<=previous) {
            return fail('Missing, duplicate or out-of-order Vulkan stage: '+stage);
        }
        previous=body.indexOf(marker);
    }
    const readbacks=body.filter(line=>line.startsWith('VK_BUFFER_READBACK='));
    if(readbacks.length!==1 || body.indexOf(readbacks[0])<=previous) return fail('Missing or ambiguous command readback');
    let words;
    try { words=JSON.parse(readbacks[0].slice('VK_BUFFER_READBACK='.length)); }
    catch { return fail('Invalid readback array'); }
    if(!Array.isArray(words) || words.length!==16 || !words.every(word=>word===0x12345678)) {
        return fail('Submitted buffer command did not produce all 16 expected words');
    }
    const pass='VULKAN_BUFFER_TRANSFER_PASS (not shader rendering or Android presentation)';
    const cleanup='VK_STAGE=cleanupDeviceWaitIdle';
    if(!once(line=>line===pass) || !once(line=>line===cleanup) ||
        body.indexOf(pass)<=body.indexOf(readbacks[0]) || body.indexOf(cleanup)<=body.indexOf(pass) ||
        body.indexOf(exits[0])<=body.indexOf(cleanup)) {
        return fail('Incomplete or out-of-order pass, cleanup and child-exit evidence');
    }
    return {ok:true,scope:'buffer_transfer_only',bufferTransferProven:true,shaderProven:false,presentationProven:false};
}
module.exports={inspectVulkanResource};
if(require.main===module) {
    if(process.argv.length!==3) {
        console.error('Usage: node scripts/assert-guest-vulkan-resource.cjs <guest-vulkan-resource-probe.txt>');
        process.exitCode=2;
    } else {
        const result=inspectVulkanResource(fs.readFileSync(process.argv[2],'utf8'));
        if(!result.ok) { console.error('RED: '+result.reason); process.exitCode=1; }
        else {
            console.log('PASS: buffer command submission and all 16 readback words verified');
            console.log('NOT YET PROVEN: GPU shaders, Weston/KMS or Android frames');
        }
    }
}
