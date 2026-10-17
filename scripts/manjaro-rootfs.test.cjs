#!/usr/bin/env node
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repo = path.resolve(__dirname, '..');

test('Manjaro image installs and packages the startx sed dependency', () => {
  const prepare = fs.readFileSync(path.join(repo, 'scripts', 'prepare-manjaro-rootfs.sh'), 'utf8');
  const bundle = fs.readFileSync(path.join(repo, 'scripts', 'build-manjaro-bundle.sh'), 'utf8');

  assert.match(prepare, /pacman -S[\s\S]*\bsed\b/);
  assert.match(bundle, /\[\[ -x "\$root\/usr\/bin\/sed" \]\]/);
  assert.match(bundle, /for attempt in 1 2 3; do[\s\S]*chroot "\$root" \/usr\/bin\/ldconfig/);
});

test('Plasma shell uses software Qt Quick and OpenGL over Venus-backed Xorg glamor', () => {
  const environment = fs.readFileSync(path.join(repo, 'guest', 'plasma', 'venus.env'), 'utf8');

  assert.match(environment, /^QT_QUICK_BACKEND=software$/m);
  assert.match(environment, /^KWIN_COMPOSE=O2$/m);
  assert.match(environment, /^QT_XCB_GL_INTEGRATION=xcb_glx$/m);
  assert.match(environment, /^KWIN_OPENGL_INTERFACE=glx$/m);
  assert.match(environment, /^LIBGL_ALWAYS_SOFTWARE=0$/m);
  assert.doesNotMatch(environment, /^LIBGL_ALWAYS_SOFTWARE=1$/m);
});

test('Plasma readiness requires active OpenGL and a Venus Zink renderer', () => {
  const session = fs.readFileSync(path.join(repo, 'guest', 'plasma', 'plasma-session'), 'utf8');

  assert.match(session, /Compositing Type: OpenGL/);
  assert.match(session, /OpenGL renderer string:.*zink.*mali/i);
  assert.match(session, /VIRUAISOR_PLASMA_HARDWARE_READY=true/);
});

test('Plasma service start completes without waiting on a lost notify socket', () => {
  const service = fs.readFileSync(path.join(repo, 'guest', 'plasma', 'avf-plasma.service'), 'utf8');
  const session = fs.readFileSync(path.join(repo, 'guest', 'plasma', 'plasma-session'), 'utf8');

  assert.match(service, /^Type=exec$/m);
  assert.doesNotMatch(service, /^NotifyAccess=/m);
  assert.doesNotMatch(session, /systemd-notify/);
});
