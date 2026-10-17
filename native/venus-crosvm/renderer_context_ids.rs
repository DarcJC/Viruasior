// Copyright 2026 The Viruaisor Authors
// SPDX-License-Identifier: BSD-3-Clause

#[derive(Debug, PartialEq, Eq)]
pub enum ContextIdError {
    DuplicateGuest(u32),
    UnknownGuest(u32),
    Exhausted,
}

pub struct RendererContextIds {
    next_renderer_id: Option<u32>,
    active: Vec<(u32, u32)>,
    renderer_to_guest: Vec<(u32, u32)>,
}

impl RendererContextIds {
    pub const fn new() -> Self {
        Self {
            next_renderer_id: Some(1),
            active: Vec::new(),
            renderer_to_guest: Vec::new(),
        }
    }

    pub fn allocate(&mut self, guest_id: u32) -> Result<u32, ContextIdError> {
        if guest_id == 0 || self.active.iter().any(|(active, _)| *active == guest_id) {
            return Err(ContextIdError::DuplicateGuest(guest_id));
        }

        let renderer_id = self.next_renderer_id.ok_or(ContextIdError::Exhausted)?;
        self.next_renderer_id = renderer_id.checked_add(1);
        self.active.push((guest_id, renderer_id));
        self.renderer_to_guest.push((renderer_id, guest_id));
        Ok(renderer_id)
    }

    pub fn resolve_guest(&self, guest_id: u32) -> Result<u32, ContextIdError> {
        if guest_id == 0 {
            return Ok(0);
        }
        self.active
            .iter()
            .find_map(|(active, renderer)| (*active == guest_id).then_some(*renderer))
            .ok_or(ContextIdError::UnknownGuest(guest_id))
    }

    pub fn resolve_host(&self, renderer_id: u32) -> Option<u32> {
        if renderer_id == 0 {
            return Some(0);
        }
        self.renderer_to_guest
            .iter()
            .find_map(|(renderer, guest)| (*renderer == renderer_id).then_some(*guest))
    }

    pub fn active_renderer_ids(&self) -> Vec<u32> {
        self.active.iter().map(|(_, renderer)| *renderer).collect()
    }

    pub fn release_guest(&mut self, guest_id: u32) -> Option<u32> {
        let index = self
            .active
            .iter()
            .position(|(active, _)| *active == guest_id)?;
        Some(self.active.swap_remove(index).1)
    }

    #[cfg(test)]
    fn with_next_renderer_id_for_test(next_renderer_id: u32) -> Self {
        Self {
            next_renderer_id: Some(next_renderer_id),
            active: Vec::new(),
            renderer_to_guest: Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reused_guest_id_gets_a_fresh_renderer_id() {
        let mut ids = RendererContextIds::new();
        let first = ids.allocate(2).unwrap();
        assert_eq!(ids.resolve_guest(2), Ok(first));
        assert_eq!(ids.release_guest(2), Some(first));

        let second = ids.allocate(2).unwrap();
        assert_ne!(first, second);
        assert_eq!(ids.resolve_host(first), Some(2));
        assert_eq!(ids.resolve_host(second), Some(2));
    }

    #[test]
    fn rejects_duplicate_active_guest_id() {
        let mut ids = RendererContextIds::new();
        ids.allocate(7).unwrap();
        assert_eq!(ids.allocate(7), Err(ContextIdError::DuplicateGuest(7)));
    }

    #[test]
    fn context_zero_passes_through_without_allocation() {
        let ids = RendererContextIds::new();
        assert_eq!(ids.resolve_guest(0), Ok(0));
        assert_eq!(ids.resolve_host(0), Some(0));
    }

    #[test]
    fn unknown_guest_id_is_rejected() {
        let ids = RendererContextIds::new();
        assert_eq!(ids.resolve_guest(9), Err(ContextIdError::UnknownGuest(9)));
    }

    #[test]
    fn active_renderer_ids_only_include_live_contexts() {
        let mut ids = RendererContextIds::new();
        let first = ids.allocate(21).unwrap();
        let second = ids.allocate(22).unwrap();
        assert_eq!(ids.active_renderer_ids(), vec![first, second]);

        assert_eq!(ids.release_guest(21), Some(first));
        assert_eq!(ids.active_renderer_ids(), vec![second]);
    }

    #[test]
    fn renderer_ids_are_never_reused_after_exhaustion() {
        let mut ids = RendererContextIds::with_next_renderer_id_for_test(u32::MAX);
        assert_eq!(ids.allocate(1), Ok(u32::MAX));
        ids.release_guest(1);
        assert_eq!(ids.allocate(2), Err(ContextIdError::Exhausted));
    }
}
