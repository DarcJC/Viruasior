const test=require('node:test');
const assert=require('node:assert/strict');
const {classifyConsoleState}=require('./guest-console-state.cjs');

// Redacted suffix of the fresh-session capture. Two serial outputs share one logger.
const interleaved=`Debian GNU/Linux 13 default hvc0

default login:
Debian GNU/Linux 13 default ttyS0

`;

test('captured interleaved banner defeats old end-of-stream check',()=>{
    assert.equal(/(?:login:|droid@default[^\n]*\$)\s*$/.test(interleaved),false);
    assert.equal(classifyConsoleState(interleaved),'login');
});
test('boot output after a fresh prompt is not a new authentication cue',()=>{
    assert.equal(classifyConsoleState(interleaved+'[ OK ] Started another service.\n'),'login');
});
test('username echo and shell prompt are distinct stages',()=>{
    assert.equal(classifyConsoleState('default login: droid\n'),'username');
    assert.equal(classifyConsoleState('droid@default:~$ \n'),'shell');
});
test('a later password or denial cannot be mistaken for an earlier shell',()=>{
    assert.equal(classifyConsoleState('droid@default:~$\nPassword:\n'),'password');
    assert.equal(classifyConsoleState('default login: droid\nLogin incorrect\n'),'denied');
});
test('return to login invalidates an older successful shell cue',()=>{
    assert.equal(classifyConsoleState('droid@default:~$\nlogout\ndefault login: \n'),'login');
});
test('commands, root prompts and other users are not accepted as droid shell',()=>{
    for(const value of ['root@default:~#','other@default:~$','echo default login:','default login: root']) {
        assert.equal(classifyConsoleState(value),'unknown');
    }
});
