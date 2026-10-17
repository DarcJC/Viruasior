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

test('Plasma shell disables the failing KWin compositor while keeping Venus available', () => {
  const environment = fs.readFileSync(path.join(repo, 'guest', 'plasma', 'venus.env'), 'utf8');

  assert.match(environment, /^QT_QUICK_BACKEND=software$/m);
  assert.match(environment, /^KWIN_COMPOSE=N$/m);
  assert.match(environment, /^QT_XCB_GL_INTEGRATION=xcb_glx$/m);
  assert.match(environment, /^KWIN_OPENGL_INTERFACE=glx$/m);
  assert.match(environment, /^LIBGL_ALWAYS_SOFTWARE=0$/m);
  assert.doesNotMatch(environment, /^LIBGL_ALWAYS_SOFTWARE=1$/m);
});

test('Plasma readiness requires disabled KWin compositing after the Venus preflight', () => {
  const session = fs.readFileSync(path.join(repo, 'guest', 'plasma', 'plasma-session'), 'utf8');

  assert.match(session, /Compositing is not active/);
  assert.match(session, /VIRUAISOR_KWIN_COMPOSITING=disabled/);
  assert.match(session, /VIRUAISOR_PLASMA_HARDWARE_READY=true/);
  assert.doesNotMatch(session, /glxinfo/);
});

test('Plasma service gates the session on a real Venus GPU draw', () => {
  const service = fs.readFileSync(path.join(repo, 'guest', 'plasma', 'avf-plasma.service'), 'utf8');
  const preflight = fs.readFileSync(path.join(repo, 'guest', 'plasma', 'venus-preflight'), 'utf8');

  assert.match(service, /ExecStartPre=.*venus-preflight/);
  assert.match(service, /ExecStartPre=\/usr\/bin\/timeout 40 /);
  assert.match(service, /^TimeoutStartSec=0$/m);
  assert.match(preflight, /driverID.*MESA_VENUS\|driverName.*venus/);
  assert.match(preflight, /guest-gpu-draw-probe\.py --venus/);
});

test('Plasma service start returns immediately without a notify handshake', () => {
  const service = fs.readFileSync(path.join(repo, 'guest', 'plasma', 'avf-plasma.service'), 'utf8');
  const session = fs.readFileSync(path.join(repo, 'guest', 'plasma', 'plasma-session'), 'utf8');

  assert.match(service, /^Type=simple$/m);
  assert.doesNotMatch(service, /^NotifyAccess=/m);
  assert.doesNotMatch(session, /systemd-notify/);
});
