package dev.vitorsilverio.armjitter.advsimd;

import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Testes do núcleo vetorial COMPARTILHADO (RFC B13.2, D1): a MESMA função de lane roda sobre os
/// dois bancos de registradores, que só diferem no mapeamento registrador→palavra.
class AdvSimdLanesTest {
    @Test
    void elementAccessCrossesTheWordBoundaryOnBothBanks() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(0, 0x1122_3344_5566_7788L);
        vfp.setD(1, 0x99AA_BBCC_DDEE_FF00L);
        // `Q0` = palavras 0 e 1; lane 8 (byte) é o primeiro byte da palavra ALTA.
        assertEquals(0x00L, AdvSimdLanes.element(vfp, 0, 8, 0));
        assertEquals(0x88L, AdvSimdLanes.element(vfp, 0, 0, 0));
        assertEquals(0x99AAL, AdvSimdLanes.element(vfp, 0, 7, 1));

        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        fp.setQ(0, 0x1122_3344_5566_7788L, 0x99AA_BBCC_DDEE_FF00L);
        assertEquals(0x00L, AdvSimdLanes.element(fp, 0, 8, 0));
        assertEquals(0x88L, AdvSimdLanes.element(fp, 0, 0, 0));
        assertEquals(0x99AAL, AdvSimdLanes.element(fp, 0, 7, 1));
    }

    @Test
    void setElementTouchesOnlyItsOwnLane() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(2, -1L);
        vfp.setD(3, -1L);
        AdvSimdLanes.setElement(vfp, 2, 1, 1, 0x1234);
        assertEquals(0xFFFF_FFFF_1234_FFFFL, vfp.d(2));
        assertEquals(-1L, vfp.d(3));
    }

    /// O caso que a API `Q`-indexada da B13.1 NÃO expressa: um operando NEON de 64 bits em `D`
    /// ÍMPAR (aqui `D5`, que é a metade ALTA de `Q2`). Na vista plana é só `baseWord = 5`.
    @Test
    void threeSameOperatesOnAnOddDoubleRegister() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(5, 0x0001_0002_0003_0004L);
        vfp.setD(7, 0x0010_0020_0030_0040L);
        AdvSimdLanes.threeSame(vfp, AdvSimdThreeSameOp.ADD, 1, 4, 9, 5, 7);
        assertEquals(0x0011_0022_0033_0044L, vfp.d(9));
        // Nenhum vizinho do par foi tocado (VFP32 nunca escreve fora do registrador nomeado).
        assertEquals(0L, vfp.d(8));
        assertEquals(0L, vfp.d(4));
    }

    @Test
    void threeSameWrapsAroundInsideEachLane() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(0, 0x0000_0000_0000_00FFL);
        vfp.setD(1, 0x0000_0000_0000_0001L);
        AdvSimdLanes.threeSame(vfp, AdvSimdThreeSameOp.ADD, 0, 8, 2, 0, 1);
        // `VADD.I8`: o carry NÃO atravessa para a lane vizinha.
        assertEquals(0x0000_0000_0000_0000L, vfp.d(2));

        AdvSimdLanes.threeSame(vfp, AdvSimdThreeSameOp.SUB, 0, 8, 3, 1, 0);
        assertEquals(0x0000_0000_0000_0002L, vfp.d(3));
    }

    /// Mesmo kernel, banco do A64: `V<n>` começa na palavra `2n`.
    @Test
    void threeSameOnAarch64BankUsesTwoWordsPerRegister() {
        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        fp.setQ(1, 0x0000_0001_0000_0002L, 0x0000_0003_0000_0004L);
        fp.setQ(2, 0x0000_0010_0000_0020L, 0x0000_0030_0000_0040L);
        AdvSimdLanes.threeSame(fp, AdvSimdThreeSameOp.ADD, 2, 4,
                3 * Aarch64FpRegisters.WORDS_PER_REGISTER,
                1 * Aarch64FpRegisters.WORDS_PER_REGISTER,
                2 * Aarch64FpRegisters.WORDS_PER_REGISTER);
        assertEquals(0x0000_0011_0000_0022L, fp.low64(3));
        assertEquals(0x0000_0033_0000_0044L, fp.high64(3));
    }
}
