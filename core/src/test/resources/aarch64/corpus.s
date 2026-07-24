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
