.text
.global _start
_start:
    mov x20, x0
    // One L1 table: code at 1 GiB, the test buffer at 2 GiB.
    ldr x0, =0x40001000
    msr ttbr0_el1, x0
    ldr x0, =0x00000000000044ff
    msr mair_el1, x0
    // 39-bit VA, 4 KiB, WB page-table walks, inner-shareable, 40-bit PA.
    ldr x0, =0x0000000280193519
    msr tcr_el1, x0
    dsb sy
    isb
    ldr x0, =0x30d01805
    msr sctlr_el1, x0
    isb
    ldr x1, =0x80000000
    ldr x2, =0x90000000
    mrs x3, sctlr_el1
    str x3, [x2, #16]
    at s1e1r, x1
    isb
    mrs x3, par_el1
    str x3, [x2, #24]
    ldr w3, =0x12345678
    str w3, [x1]
    dsb sy
    ldr w3, [x1]
    str w3, [x2]
    cbz x20, 2f
    // Host changes the DMA-BUF after this exit. Read before and after the
    // host invalidates the cache line through its mapping on another CPU.
    ldr w3, [x1]
    str w3, [x2, #32]
    ldr w3, [x1]
    str w3, [x2, #40]
    ldr w3, =0x12345678
    str w3, [x1]
2:
    // Positive control: explicit cleaning must make the same store visible.
    dc civac, x1
    dsb sy
    ldr w3, [x1]
    str w3, [x2, #8]
1:  b 1b
.ltorg
