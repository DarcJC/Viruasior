const test = require('node:test');
const assert = require('node:assert/strict');
const {encodeConsoleScript} = require('./guest-console-command.cjs');
const source = "printf '\\nDIAG_NATIVE_COMPARE_BEGIN\\n'; id; printf 'NATIVE_FULL_EXIT=%s\\n' \"$?\"; printf '\\nDIAG_NATIVE_COMPARE_END\\n'\n";
const fs = require('node:fs');
const shell = process.platform === 'win32'
    ? (process.env.ProgramFiles || 'C:/Program Files') + '/Git/bin/bash.exe'
    : '/bin/sh';

// Serial/readline echoes may be wrapped/redrawn into marker-looking lines. Transport
// must not contain literal result markers; decoding/execution still happens in the guest.
test('marker-bearing diagnostic source is absent from the echoed wire command', () => {
    const wire = encodeConsoleScript(source);
    assert.equal(wire.includes('DIAG_NATIVE_COMPARE_BEGIN'), false);
    assert.equal(wire.includes('DIAG_NATIVE_COMPARE_END'), false);
    assert.equal(wire.includes('NATIVE_FULL_EXIT='), false);
});
test('encoded command reconstructs the exact public script, including quoting and UTF-8', () => {
    const original = source + "printf '%s' '测试 \"quoted\"'\n";
    const wire = encodeConsoleScript(original);
    const match = wire.match(/^printf '%s' '([A-Za-z0-9+/=]+)' \| base64 -d \| \/bin\/sh\n$/);
    assert.ok(match, 'expected a non-interactive encoded shell script');
    assert.equal(Buffer.from(match[1], 'base64').toString('utf8'), original);
});
test('the wire stays below the canonical TTY limit and has one input newline', () => {
    const wire = encodeConsoleScript(source);
    assert.ok(Buffer.byteLength(wire) <= 3000);
    assert.equal(wire.split('\n').length, 2);
});
test('wire runs the public script through a real non-interactive shell', {skip: !fs.existsSync(shell)}, () => {
    const script = "printf '%s\\n' 'DIAG_NATIVE_COMPARE_BEGIN' 'quoted \"input\"' 'DIAG_NATIVE_COMPARE_END'\n";
    const result = require('node:child_process').execFileSync(shell, ['-c', encodeConsoleScript(script)], {
        encoding: 'utf8', timeout: 5000,
    });
    assert.equal(result, 'DIAG_NATIVE_COMPARE_BEGIN\nquoted "input"\nDIAG_NATIVE_COMPARE_END\n');
});

test('public probe path is inherited by the non-interactive shell and unset afterward', {skip: !fs.existsSync(shell)}, () => {
    const wire="GPU_PROBE='/tmp/public-probe.py'\nexport GPU_PROBE\n"+
        encodeConsoleScript('printf "PROBE_PATH=%s\\n" "$GPU_PROBE"\n')+
        'unset GPU_PROBE\nprintf "AFTER=%s\\n" "${GPU_PROBE-unset}"\n';
    const result=require('node:child_process').execFileSync(shell,['-c',wire],{encoding:'utf8',timeout:5000});
    assert.equal(result,'PROBE_PATH=/tmp/public-probe.py\nAFTER=unset\n');
});

test('invalid or oversized scripts fail before sending anything', () => {
    for (const value of ['', null, 'echo\0broken', 'x'.repeat(3000)]) {
        assert.throws(() => encodeConsoleScript(value));
    }
});
