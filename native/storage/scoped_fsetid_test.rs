//! Exercises the actual scoped capability implementation extracted from crosvm by the runner.
#[allow(dead_code)]
mod caps { include!(env!("VIRUAISOR_CAPS_SOURCE")); }
use caps::{Capability, Caps, Set as CapSet, Value as CapValue};
use std::io;

// A restore error is itself a regression; make the production error path fail this test.
macro_rules! error { ($($args:tt)*) => { panic!($($args)*) }; }
include!(env!("VIRUAISOR_SCOPED_SOURCE"));

fn flags(name: &str) -> u64 {
    let status = std::fs::read_to_string("/proc/thread-self/status").unwrap();
    u64::from_str_radix(status.lines().find(|line| line.starts_with(name)).unwrap()
        .split_once(':').unwrap().1.trim(), 16).unwrap()
}

#[test]
fn present_effective_capability_is_restored() {
    let before = flags("CapEff:");
    assert_ne!(before & (1 << 4), 0, "runner requires effective CAP_FSETID for this case");
    let guard = drop_cap_fsetid().unwrap();
    assert_eq!(flags("CapEff:"), before & !(1 << 4));
    drop(guard);
    assert_eq!(flags("CapEff:"), before);
}

#[test]
fn permitted_but_initially_disabled_capability_stays_disabled() {
    assert_ne!(flags("CapPrm:") & (1 << 4), 0);
    let original = Caps::for_current_thread().unwrap();
    let mut disabled = Caps::for_current_thread().unwrap();
    disabled.update(&[Capability::Fsetid], CapSet::Effective, CapValue::Clear).unwrap();
    disabled.apply().unwrap();
    let before = flags("CapEff:");
    let guard = drop_cap_fsetid().unwrap();
    assert_eq!(flags("CapEff:"), before);
    drop(guard);
    let after = flags("CapEff:");
    original.apply().unwrap();
    assert_eq!(after, before, "scope must not add an effective capability it did not remove");
}

#[test]
fn without_permitted_capabilities_no_restore_is_attempted() {
    assert_eq!(flags("CapPrm:"), 0, "runner must execute this case with an empty permitted set");
    let before = flags("CapEff:");
    let guard = drop_cap_fsetid().unwrap();
    assert_eq!(flags("CapEff:"), before);
    drop(guard);
    assert_eq!(flags("CapEff:"), before);
    assert_eq!(flags("CapPrm:"), 0);
}
