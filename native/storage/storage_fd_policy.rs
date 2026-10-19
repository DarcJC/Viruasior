//! Fixed, opt-in AVF compatibility contract. Compiled standalone for regression tests.
use std::path::{Path, PathBuf};

pub const MARKER: &str = "viruaisor.storage_fd=1";
pub const MOUNT: &str = "systemd.mount-extra=viruaisor-storage-0:/mnt/shared:virtiofs:rw,nodev,nosuid,nofail,x-systemd.device-timeout=10s";

pub fn frontend_plan(
    params: &[String],
    switches: &[&Path],
    has_fs: bool,
) -> Result<Option<PathBuf>, String> {
    let tokens: Vec<&str> = params.iter().flat_map(|value| value.split_ascii_whitespace()).collect();
    let markers: Vec<_> = tokens.iter().filter(|value| value.starts_with("viruaisor.storage_fd=")).collect();
    if markers.is_empty() { return Ok(None); }
    let mounts: Vec<_> = tokens.iter().filter(|value| value.starts_with("systemd.mount-extra=viruaisor-storage-0:")).collect();
    if markers != vec![&MARKER] || mounts != vec![&MOUNT] || has_fs || switches.len() != 1 {
        return Err("storage FD requires one fixed marker, mount, switches socket and no existing FS frontend".into());
    }
    let path = switches[0].to_str().ok_or("storage FD path is not UTF-8")?;
    let fd = path.strip_prefix("/proc/self/fd/").ok_or("storage FD must be inherited")?;
    let number: i32 = fd.parse().map_err(|_| "invalid storage FD number")?;
    if number < 3 || fd != number.to_string() {
        return Err("storage FD must be a canonical non-stdio descriptor".into());
    }
    Ok(Some(path.into()))
}

pub fn owned_backend(
    owner: Option<i32>,
    tag: &str,
    fd: Option<i32>,
    client_fd: Option<i32>,
    path_socket: bool,
    disable_sandbox: bool,
    skip_pivot_root: bool,
    parent: i32,
) -> Result<bool, String> {
    let Some(owner) = owner else {
        return if client_fd.is_none() { Ok(false) } else { Err("client pidfd requires an owned backend".into()) };
    };
    if owner <= 1 || owner != parent || tag != "viruaisor-storage-0" || fd != Some(3)
        || client_fd != Some(5) || path_socket || !disable_sandbox || !skip_pivot_root {
        return Err("app storage backend requires its live parent, fixed tag/FD and unprivileged profile".into());
    }
    Ok(true)
}

pub fn backend_outcome<Executor: std::fmt::Display, Backend: std::fmt::Display>(
    result: Result<Result<(), Backend>, Executor>,
) -> Result<(), String> {
    result.map_err(|error| format!("FS executor: {error:#}"))?
        .map_err(|error| format!("FS backend: {error:#}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn params() -> Vec<String> { vec![format!("root=/dev/vda1 {MARKER} {MOUNT}")] }
    fn fd() -> &'static Path { Path::new("/proc/self/fd/37") }

    #[test]
    fn only_explicit_fixed_pair_converts_an_inherited_fd() {
        assert_eq!(frontend_plan(&params(), &[fd()], false), Ok(Some(fd().into())));
    }

    #[test]
    fn ordinary_switch_input_is_untouched() {
        assert_eq!(frontend_plan(&[MOUNT.into()], &[Path::new("/dev/input/0")], true), Ok(None));
    }

    #[test]
    fn opt_in_requires_exactly_one_mount_and_marker() {
        for value in [MARKER.to_string(), format!("{MARKER} {MOUNT} {MOUNT}"),
            format!("{MARKER} {MARKER} {MOUNT}"), format!("viruaisor.storage_fd=2 {MOUNT}"),
            format!("{MARKER} {MOUNT}x"), format!("{MARKER} {MOUNT} systemd.mount-extra=viruaisor-storage-0:/etc:virtiofs:rw")] {
            assert!(frontend_plan(&[value.clone()], &[fd()], false).is_err(), "{}", value);
        }
    }

    #[test]
    fn opt_in_rejects_missing_ambiguous_and_existing_fs_devices() {
        assert!(frontend_plan(&params(), &[], false).is_err());
        assert!(frontend_plan(&params(), &[fd(), fd()], false).is_err());
        assert!(frontend_plan(&params(), &[fd()], true).is_err());
    }

    #[test]
    fn path_must_be_one_inherited_non_stdio_fd() {
        for value in ["/tmp/storage.sock", "/proc/123/fd/3", "/proc/self/fd/../fd/3",
            "/proc/self/fd/+3", "/proc/self/fd/", "/proc/self/fd/2", "/proc/self/fd/2147483648",
            "/proc/self/fd/3/", "/proc/self/fd/03"] {
            assert!(frontend_plan(&params(), &[Path::new(value)], false).is_err(), "{}", value);
        }
    }

    #[test]
    fn owned_backend_requires_fixed_fd_tag_parent_and_non_root_sandbox_profile() {
        assert_eq!(owned_backend(Some(1234), "viruaisor-storage-0", Some(3), Some(5), false, true, true, 1234), Ok(true));
        assert_eq!(owned_backend(None, "anything", None, None, true, false, false, 1), Ok(false));
        assert!(owned_backend(Some(1234), "other", Some(3), Some(5), false, true, true, 1234).is_err());
        assert!(owned_backend(Some(1234), "viruaisor-storage-0", Some(4), Some(5), false, true, true, 1234).is_err());
        assert!(owned_backend(Some(1234), "viruaisor-storage-0", Some(3), Some(5), true, true, true, 1234).is_err());
        assert!(owned_backend(Some(1234), "viruaisor-storage-0", Some(3), Some(5), false, false, true, 1234).is_err());
        assert!(owned_backend(Some(1234), "viruaisor-storage-0", Some(3), Some(5), false, true, false, 1234).is_err());
        assert!(owned_backend(Some(1234), "viruaisor-storage-0", Some(3), Some(5), false, true, true, 1).is_err());
        assert!(owned_backend(Some(1), "viruaisor-storage-0", Some(3), Some(5), false, true, true, 1).is_err());
    }

    #[test]
    fn owned_backend_requires_the_fixed_client_pidfd() {
        assert!(owned_backend(Some(1234), "viruaisor-storage-0", Some(3), None, false, true, true, 1234).is_err());
        assert!(owned_backend(Some(1234), "viruaisor-storage-0", Some(3), Some(4), false, true, true, 1234).is_err());
        assert!(owned_backend(None, "viruaisor-storage-0", Some(3), Some(5), false, true, true, 1234).is_err());
    }

    #[test]
    fn backend_inner_failure_is_not_a_successful_exit() {
        assert_eq!(backend_outcome::<&str, &str>(Ok(Err("guest memory mapping denied"))),
            Err("FS backend: guest memory mapping denied".into()));
    }

    #[test]
    fn backend_executor_failure_keeps_its_distinct_context() {
        assert_eq!(backend_outcome::<&str, &str>(Err("poll error")), Err("FS executor: poll error".into()));
    }

    #[test]
    fn backend_clean_shutdown_is_successful() {
        assert_eq!(backend_outcome::<&str, &str>(Ok(Ok(()))), Ok(()));
    }
}
