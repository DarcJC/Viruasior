// Public diagnostic scripts only; never pass credentials through this helper.
function encodeConsoleScript(script) {
    if (typeof script !== 'string' || !script || script.includes('\0')) {
        throw new Error('Expected a non-empty public shell script without NUL');
    }
    const encoded = Buffer.from(script, 'utf8').toString('base64');
    // Echo contains no literal output markers. A non-interactive guest shell executes
    // the original bytes; no terminal settings, permissions or credentials are changed.
    const command = "printf '%s' '" + encoded + "' | base64 -d | /bin/sh\n";
    if (Buffer.byteLength(command, 'utf8') > 3000) {
        throw new Error('Public diagnostic exceeds the safe canonical TTY line limit');
    }
    return command;
}
module.exports = {encodeConsoleScript};
