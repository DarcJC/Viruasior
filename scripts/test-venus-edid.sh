#!/usr/bin/env bash
# Exercise the actual patched upstream EDID encoder without the device crate's host dependencies.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source=${CROSVM_EDID_SOURCE:-"$repo/.pi/device-debug/venus-crosvm-build/crosvm/devices/src/virtio/gpu/edid.rs"}
[[ -f "$source" ]]
work=$(mktemp -d /tmp/viruaisor-edid-tests.XXXXXX)
cat > "$work/test.rs" <<EOF
#![allow(dead_code)]
mod virtio { pub mod gpu {
    pub struct GpuDisplayParameters { pub width:u32,pub height:u32,pub refresh_rate:u32 }
    impl GpuDisplayParameters {
        pub fn get_virtual_display_size(&self)->(u32,u32) {(self.width,self.height)}
        pub fn horizontal_dpi(&self)->u32 {160}
        pub fn vertical_dpi(&self)->u32 {160}
    }
    mod protocol {
        #[derive(Debug)] pub enum GpuResponse { OkEdid(Box<super::edid::EdidBytes>), ErrEdid(String), OkNoData }
        pub type VirtioGpuResult=Result<GpuResponse,GpuResponse>;
    }
    #[path="$source"] mod edid;
    #[cfg(test)] mod tests {
        use super::*;
        fn bytes(w:u32,h:u32)->Box<edid::EdidBytes> {
            let info=edid::DisplayInfo::new(&GpuDisplayParameters {width:w,height:h,refresh_rate:60});
            match edid::EdidBytes::new(&info).unwrap() {protocol::GpuResponse::OkEdid(bytes)=>bytes,_=>unreachable!()}
        }
        fn timing(d:&[u8])->(u32,u32,f64) {
            let w=d[2] as u32 | ((d[4]&0xf0) as u32)<<4;
            let h=d[5] as u32 | ((d[7]&0xf0) as u32)<<4;
            let hb=d[3] as u32 | ((d[4]&0x0f) as u32)<<8;
            let vb=d[6] as u32 | ((d[7]&0x0f) as u32)<<8;
            let clock=u16::from_le_bytes([d[0],d[1]]) as u32*10000;
            (w,h,clock as f64/((w+hb)*(h+vb)) as f64)
        }
        #[test] fn native_sixty_and_desktop_one_twenty_are_both_real_timings() {
            let bytes=bytes(3408,2272);let data=bytes.as_bytes();
            let (w,h,hz)=timing(&data[54..72]);
            assert_eq!((w,h),(3408,2272));assert!((hz-60.0).abs()<0.01);
            let (w,h,hz)=timing(&data[90..108]);
            assert_eq!((w,h),(1920,1200));assert!((hz-120.0).abs()<0.01);
            for block in data.chunks(128) {assert_eq!(block.iter().fold(0u8,|a,v|a.wrapping_add(*v)),0);}
        }
        #[test] fn smaller_guests_keep_their_original_mode_budget() {
            let bytes=bytes(1280,720);
            assert!(bytes.as_bytes()[90..108].iter().all(|v|*v==0));
        }
    }
} }
EOF
rustc --edition=2021 --test "$work/test.rs" -o "$work/test"
"$work/test"
