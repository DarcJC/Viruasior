// Copyright 2026 The Viruaisor Authors
// SPDX-License-Identifier: BSD-3-Clause

pub fn renderer_context_init(guest_init: u32, venus_only: bool, venus_capset: u32) -> u32 {
    if guest_init == 0 && venus_only {
        venus_capset
    } else {
        guest_init
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VENUS_CAPSET: u32 = 4;

    #[test]
    fn venus_only_renderer_maps_default_context_to_venus() {
        assert_eq!(renderer_context_init(0, true, VENUS_CAPSET), VENUS_CAPSET);
    }

    #[test]
    fn explicit_context_init_is_preserved() {
        assert_eq!(
            renderer_context_init(VENUS_CAPSET, true, VENUS_CAPSET),
            VENUS_CAPSET
        );
        assert_eq!(renderer_context_init(2, true, VENUS_CAPSET), 2);
    }

    #[test]
    fn renderer_with_virgl_keeps_default_context() {
        assert_eq!(renderer_context_init(0, false, VENUS_CAPSET), 0);
    }
}
