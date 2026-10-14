#!/usr/bin/env node
// Host configuration gate only. This does NOT prove guest GPU execution or visible frames.
const fs = require('node:fs');

function inspectGfxstreamVulkan(text) {
    const lines = text.split(/\r?\n/).filter(line => !line.includes('Console('));
    const starts = lines.filter(line => /virtmgr\s*:.*Running crosvm with args:/.test(line));
    if (starts.length !== 1) return {ok:false, reason:'Expected exactly one crosvm launch in the scoped capture'};
    const gpu = starts[0].match(/"--gpu=([^"\n]+)"/)?.[1];
    if (!gpu || !/(?:^|,)backend=gfxstream(?:,|$)/.test(gpu)) {
        return {ok:false, reason:'The actual crosvm GPU backend is not gfxstream'};
    }
    if (!lines.some(line => /GFXSTREAM\s*:.*Gfxstream initialized successfully!/.test(line))) {
        return {ok:false, reason:'gfxstream initialization is not confirmed'};
    }
    if (!/(?:^|,)vulkan=true(?:,|$)/.test(gpu)) {
        return {ok:false, reason:'The actual crosvm arguments do not request vulkan=true',gpu};
    }
    if (!/(?:^|,)surfaceless=true(?:,|$)/.test(gpu)) {
        return {ok:false, reason:'The actual crosvm arguments do not request surfaceless=true',gpu};
    }
    if (!/(?:^|,)egl=true(?:,|$)/.test(gpu) || !/(?:^|,)gles=true(?:,|$)/.test(gpu)) {
        return {ok:false, reason:'The actual crosvm arguments do not keep the compatible GL compositor path',gpu};
    }
    if (/(?:^|,)glx=true(?:,|$)/.test(gpu)) {
        return {ok:false, reason:'The actual crosvm arguments unexpectedly enable GLX',gpu};
    }
    if (!/(?:^|,)context-types=gfxstream-gles:gfxstream-vulkan:gfxstream-composer(?:,|$)/.test(gpu)) {
        return {ok:false, reason:'The actual crosvm arguments do not select the compatible gfxstream GLES/Vulkan/composer capsets',gpu};
    }
    const flags = lines.filter(line => /GFXSTREAM\s*:.*\bVulkan: (?:enabled|disabled)\b/.test(line));
    if (flags.length !== 1 || !/\bVulkan: enabled\b/.test(flags[0])) {
        return {ok:false, reason:'gfxstream Vulkan is disabled, missing or ambiguous',gpu};
    }
    return {ok:true,gpu};
}

module.exports = {inspectGfxstreamVulkan};
if (require.main === module) {
    if (process.argv.length !== 3) {
        console.error('Usage: node scripts/assert-gfxstream-vulkan.cjs <scoped-logcat.txt>');
        process.exitCode=2;
    } else {
        const result=inspectGfxstreamVulkan(fs.readFileSync(process.argv[2],'utf8'));
        if (!result.ok) {
            console.error('RED: '+result.reason);
            process.exitCode=1;
        } else {
            console.log('PASS: actual gfxstream backend initialized with Vulkan enabled');
            console.log('NOT YET PROVEN: guest hardware GPU execution or visible frames');
        }
    }
}
