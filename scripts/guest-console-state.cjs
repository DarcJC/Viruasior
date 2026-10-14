// State cues for this test image's line-logged serial console, not an authentication bypass.
// getty banners/boot output may follow a prompt; require fresh input/output in the caller.
function classifyConsoleState(text) {
    const lines=text.split(/\r?\n/);
    let state='unknown';
    for (const line of lines) {
        if (/^\s*default login:[\t ]*$/.test(line)) state='login';
        else if (/^\s*default login:[\t ]*droid[\t ]*$/.test(line)) state='username';
        else if (/^\s*droid@default[^\n]*\$[\t ]*$/.test(line)) state='shell';
        else if (/^\s*Password:[\t ]*$/.test(line)) state='password';
        else if (/^\s*(?:Login incorrect|Authentication failure|Login timed out[^\n]*)[\t ]*$/.test(line)) state='denied';
    }
    return state;
}
module.exports={classifyConsoleState};
