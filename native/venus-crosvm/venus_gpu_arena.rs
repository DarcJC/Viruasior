// Copyright 2026 Viruaisor contributors. BSD-3-Clause.
// Android's Mali DMA-BUF PFNMAP VMA does not describe a physically contiguous
// block. A differently aligned host/guest BAR prevents KVM's stage-2 huge-map
// heuristic from treating that VMA as a contiguous 2 MiB allocation.
const PMD_SIZE: usize = 2 * 1024 * 1024;

fn prefix(host: usize, guest: u64, page: usize) -> usize {
    if host % PMD_SIZE == (guest % PMD_SIZE as u64) as usize { page } else { 0 }
}

fn translated_offset(offset: usize, size: usize, visible: usize, prefix: usize) -> Option<usize> {
    (offset.checked_add(size)? <= visible).then(|| offset.checked_add(prefix)).flatten()
}

#[cfg(not(test))]
mod mapping {
    use super::*;
    use base::{AsRawDescriptor, MappedRegion, MemoryMappingArena, MmapError, Protection};

    struct GpuArena {
        backing: MemoryMappingArena,
        prefix: usize,
        size: usize,
    }

    pub fn create(size: usize, guest: u64) -> Result<Box<dyn MappedRegion>, MmapError> {
        let page=base::pagesize();
        if page >= PMD_SIZE || size % page != 0 { return Err(MmapError::InvalidAddress); }
        let reserved=size.checked_add(page).ok_or(MmapError::InvalidAddress)?;
        let backing=MemoryMappingArena::new(reserved)?;
        let prefix=prefix(backing.as_ptr() as usize,guest,page);
        base::info!("VIRUAISOR_GPU_PAGE_MAPPING guest={:#x} host={:#x} size={:#x} prefix={}",
            guest,backing.as_ptr() as usize+prefix,size,prefix);
        Ok(Box::new(GpuArena { backing,prefix,size }))
    }

    // SAFETY: backing owns the complete reservation including prefix and tail.
    // Every submapping is checked against the smaller guest-visible range.
    unsafe impl MappedRegion for GpuArena {
        fn as_ptr(&self) -> *mut u8 {
            // SAFETY: prefix is at most one page inside our owned reservation.
            unsafe { self.backing.as_ptr().add(self.prefix) }
        }
        fn size(&self) -> usize { self.size }
        fn add_fd_mapping(&mut self,offset: usize,size: usize,fd: &dyn AsRawDescriptor,
            fd_offset: u64,prot: Protection) -> Result<(),MmapError> {
            let offset=translated_offset(offset,size,self.size,self.prefix)
                .ok_or(MmapError::InvalidAddress)?;
            self.backing.add_fd_mapping(offset,size,fd,fd_offset,prot)
        }
        fn remove_mapping(&mut self,offset: usize,size: usize) -> Result<(),MmapError> {
            let offset=translated_offset(offset,size,self.size,self.prefix)
                .ok_or(MmapError::InvalidAddress)?;
            self.backing.remove_mapping(offset,size)
        }
    }
}
#[cfg(not(test))]
pub use mapping::create;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_page_aligned_bar_forbids_stage2_block_mapping() {
        for page in [4096, 16384, 65536] {
            for host in (0..PMD_SIZE).step_by(page) {
                for guest in (0..PMD_SIZE).step_by(page) {
                    let shift=prefix(host,guest as u64,page);
                    assert!(shift <= page && shift % page == 0);
                    assert_ne!((host+shift)%PMD_SIZE,guest%PMD_SIZE);
                }
            }
        }
    }

    #[test]
    fn mappings_cannot_reach_the_reserved_guard_or_overflow() {
        assert_eq!(translated_offset(0,4096,8192,4096),Some(4096));
        assert_eq!(translated_offset(4096,4096,8192,4096),Some(8192));
        assert_eq!(translated_offset(8192,4096,8192,4096),None);
        assert_eq!(translated_offset(usize::MAX,4096,8192,4096),None);
        assert_eq!(translated_offset(usize::MAX,0,usize::MAX,4096),None);
    }
}
