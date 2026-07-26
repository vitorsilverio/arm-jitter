.text
.global _start
_start:
adr x0, _start
adr x1, label1
adrp x2, _start
adrp x3, label1
add x4, x5, #0x123
add x4, x5, #0x123, lsl #12
adds x6, x7, #0
add sp, sp, #0x10
sub x8, x9, #7
subs x10, x11, #1
sub sp, sp, #0x20
add w12, w13, #5
adds w14, w15, #0xff
movz x0, #0x1234
movz x0, #0x1234, lsl #16
movz x0, #0x1234, lsl #32
movz x0, #0x1234, lsl #48
movn x1, #0xabcd
movk x2, #0x5678, lsl #16
movz w3, #0x9999
movz xzr, #1
b label1
bl label1
b.eq label1
b.ne label1
b.ge label1
cbz x0, label1
cbnz x0, label1
cbz w1, label1
tbz x2, #5, label1
tbnz x2, #40, label1
br x9
blr x10
ret
ret x11
svc #0x1234
label1:
nop
ldr x4, [x5]
ldr x4, [x5, #16]
str x4, [x5, #16]
ldr w4, [x5, #8]
ldrb w4, [x5, #1]
ldrh w4, [x5, #2]
ldrsb x4, [x5]
ldrsb w4, [x5]
ldrsh x4, [x5]
ldrsh w4, [x5]
ldrsw x4, [x5]
ldur x4, [x5, #-8]
stur x4, [x5, #-8]
ldur w4, [x5, #-4]
ldr x4, [x5, #8]!
str x4, [x5, #8]!
ldr x4, [x5], #8
str x4, [x5], #8
ldrb w4, [x5], #1
ldr x4, [x5, x6]
ldr x4, [x5, x6, lsl #3]
ldr x4, [x5, w6, sxtw]
ldr x4, [x5, w6, sxtw #3]
ldr x4, [x5, x6, sxtx #3]
ldr w4, [x5, w6, uxtw #2]
stp x29, x30, [sp, #-16]!
ldp x29, x30, [sp], #16
stp x0, x1, [sp, #16]
ldp w0, w1, [sp, #8]
ldp x0, x1, [sp, #16]!
stp x0, x1, [sp], #16
ldr x7, litlabel
litlabel:
nop

// ── B6.3.1: logical (immediate) + ALU registrador (shifted/extended) ─────────────────────
and x0, x1, #0x1
orr x2, x3, #0x5555555555555555
eor x4, x5, #0x1111111111111111
ands x6, x7, #0xfffffffffffffffe
and w8, w9, #0xaaaaaaaa
ands w10, w11, #0x80000001

add x0, x1, x2
add x0, x1, x2, lsl #4
sub x3, x4, x5, lsr #6
subs x6, x7, x8, asr #8
adds w9, w10, w11
add w12, w13, w14, lsr #3
sub w15, w16, w17, asr #7

add x0, x1, x2, uxtb
add x0, x1, x2, uxth
add x0, x1, x2, uxtw
add x0, x1, x2, uxtx
add x0, x1, x2, sxtb
add x0, x1, x2, sxth
add x0, x1, x2, sxtw
add x0, x1, x2, sxtx
add sp, sp, x1, uxtx
add sp, sp, x1, uxtx #3
add sp, x4, x5, uxtx
adds x2, sp, x3, uxtx
adds xzr, x1, x2, uxtx
subs xzr, x1, x2, uxtx
add w0, w1, w2, uxtb #2

// ── B6.3.2: CSEL/CSINC/CSINV/CSNEG (+ aliases) + SBFM/UBFM/BFM (+ aliases) ────────────────
csel x0, x1, x2, eq
csinc x3, x4, x5, ne
csinv x6, x7, x8, cs
csneg x9, x10, x11, cc
csel w20, w21, w22, gt
cset x12, eq
csetm x13, ne
cinc x14, x15, eq
cinv x16, x17, ne
cneg x18, x19, eq

sbfm x0, x1, #4, #10
sbfm x2, x3, #10, #4
ubfm x4, x5, #4, #10
ubfm x6, x7, #10, #4
bfm x8, x9, #4, #10
bfm x10, x11, #10, #4

lsl x12, x13, #5
lsr x14, x15, #5
asr x16, x17, #5
ubfx x18, x19, #8, #16
sbfx x20, x21, #8, #16
bfi x22, x23, #8, #16
bfxil x24, x25, #8, #16
uxtb w26, w27
uxth w28, w29
sxtb x30, w0
sxth x1, w2
sxtw x3, w4
csneg x25, x26, xzr, eq
