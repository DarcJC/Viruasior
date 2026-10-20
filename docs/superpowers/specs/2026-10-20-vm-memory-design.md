# VM memory configuration

Approved by the user on 2026-10-20; implementation is authorized without another review gate.

Add an Adjust action beside allocated memory in the workbench Control panel. The dialog
accepts an integer MiB value, with 2, 4 and 8 GiB presets. Validate the existing
256–32768 MiB bundle range and the device's total physical memory; disable presets
that exceed the device limit. Unknown host memory blocks saving with a readable error.

Only a stopped or crashed VM with an imported bundle and no conflicting operation may
change memory. Check the gate in both UI and service, and serialize the write with VM
start, stop, import and disk operations. Confirm that no application-owned VM remains
running before writing. A configuration error must not change VM lifecycle status.

Persist only memoryMiB in the current bundle's vm.json using an atomic file write.
Preserve other manifest fields and recover interrupted writes when loading. Publish
the new bundle value only after persistence succeeds. The existing AVF
setMemoryBytes conversion applies the new value at the next start; no automatic stop
or start occurs. Replacing the bundle uses the incoming manifest's value.

Implementation and validation:

1. Test integer/range/physical-memory validation and conflicting-action gates.
2. Add atomic bundle persistence, service command, busy/error/success state and UI wiring.
3. Test persistence/reload, interrupted and failed writes, dialog presets/validation and
   disabled controls. Run :app:testDebugUnitTest and :app:assembleDebug.
4. If device validation is available, use a separate application ID and synthetic bundle
   so the user's imported VM and running session are preserved.

## Validation completed on 2026-10-20

- Local Gradle unit tests: 203 discovered, 202 passed, 1 skipped, 0 failures.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed.
- M367FC / Android 17, separate application ID `pro.darc.viruaisor.memorytest`:
  `VmMemoryConfigurationTest` (5 tests) and `WorkbenchUiTest` (9 tests) passed,
  `OK (14 tests)`. Tests cover persistence/reload, preservation of other manifest
  fields and rootfs bytes, invalid values, failed/interrupted writes, legacy backup
  recovery, compact-layout dialog validation/preset submission and active-state
  disabling, plus existing workbench navigation/display regressions.
- Device install restrictions required installing the main and test APKs separately
  with `adb install -r -t`, followed by direct `am instrument -w -r`. The Gradle
  connected-test task returned success despite installation failure, so it was not
  counted as a test pass; the direct runner's 14-test result is the device evidence.
- Tests used synthetic bundles and UI states. No real VM was booted or resized, and
  the user's existing package, imported bundle and Android permissions were unchanged.
