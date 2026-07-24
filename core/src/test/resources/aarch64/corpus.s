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
