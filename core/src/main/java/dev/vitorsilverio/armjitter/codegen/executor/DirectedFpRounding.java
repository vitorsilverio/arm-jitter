package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.FpRoundingMode;

import java.math.BigDecimal;
import java.math.MathContext;

/// Arredondamento dirigido (task B3.8): dado o resultado já corretamente arredondado para
/// "round-to-nearest" que Java produz nativamente (garantido correto pela JLS para `+`, `-`,
/// `*`, `/` e `Math.sqrt`), e uma aproximação de alta precisão do valor matemático exato,
/// decide se o resultado deve ser ajustado ±1 ULP para satisfazer `RMODE`≠round-to-nearest.
///
/// Técnica: como o candidato "round-to-nearest" (`rn`) está a no máximo 0,5 ULP do valor exato,
/// o sinal de `exato − rn` já diz tudo: se `exato > rn`, o vizinho representável imediatamente
/// acima é o arredondamento correto para cima; se `exato < rn`, o vizinho abaixo é o correto para
/// baixo; se são iguais, `rn` já é exato e nenhum modo de arredondamento muda o resultado. Não há
/// necessidade de reimplementar a aritmética de ponto flutuante — só decidir a direção.
///
/// Precisão do "exato": `ADD`/`SUB`/`MUL` de `float` cabem exatamente em `double` (mantissa de
/// 24 bits nunca excede os 53 bits de `double`); `ADD`/`SUB`/`MUL` de `double` usam
/// {@link BigDecimal} exato (sem `MathContext`, portanto sem perda). `DIV`/`SQRT` não têm
/// resultado exato representável em precisão finita — usa-se uma aproximação de altíssima
/// precisão ({@link #EXACT_APPROXIMATION_CONTEXT}, muito além da precisão do tipo de destino),
/// cujo erro residual é astronomicamente menor que 1 ULP do tipo de destino; a única forma de
/// discordância seria o valor real cair exatamente em cima de um ponto médio de arredondamento
/// simultaneamente na precisão de destino E dentro dessa margem de aproximação — não observado
/// em nenhum binário real (ver spec B3.8, seção "Limitações conhecidas").
public final class DirectedFpRounding {
    /// Dígitos decimais usados para aproximar o resultado exato de `DIV`/`SQRT` de `double`
    /// (bem além dos ~17 dígitos decimais que `double` consegue representar).
    private static final MathContext EXACT_APPROXIMATION_CONTEXT = new MathContext(60);

    private DirectedFpRounding() {
    }

    /// Arredonda o resultado de uma operação `float`, dado o candidato round-to-nearest nativo
    /// (`nearest`) e o valor exato (ou quase-exato, ver a classe) em `double`.
    public static float roundFloat(float nearest, double exact, FpRoundingMode mode) {
        if (mode == FpRoundingMode.ROUND_TO_NEAREST
                || Float.isNaN(nearest) || Float.isInfinite(nearest)) {
            return nearest;
        }
        double diff = exact - (double) nearest;
        if (diff == 0.0) {
            return nearest;
        }
        boolean towardPlusInfinity = wantsPlusInfinity(mode, exact < 0.0);
        if (towardPlusInfinity) {
            return diff > 0.0 ? Math.nextUp(nearest) : nearest;
        }
        return diff < 0.0 ? Math.nextDown(nearest) : nearest;
    }

    /// Arredonda o resultado de uma operação `double`, dado o candidato round-to-nearest nativo
    /// (`nearest`) e o valor exato (ou quase-exato, ver a classe) como {@link BigDecimal}.
    public static double roundDouble(double nearest, BigDecimal exact, FpRoundingMode mode) {
        if (mode == FpRoundingMode.ROUND_TO_NEAREST
                || Double.isNaN(nearest) || Double.isInfinite(nearest)) {
            return nearest;
        }
        int cmp = exact.compareTo(new BigDecimal(nearest));
        if (cmp == 0) {
            return nearest;
        }
        boolean towardPlusInfinity = wantsPlusInfinity(mode, exact.signum() < 0);
        if (towardPlusInfinity) {
            return cmp > 0 ? Math.nextUp(nearest) : nearest;
        }
        return cmp < 0 ? Math.nextDown(nearest) : nearest;
    }

    /// Placeholder devolvido quando o valor exato não é representável em {@link BigDecimal}
    /// (operando `NaN`/infinito) ou a operação em si não tem valor exato finito (divisão por
    /// zero, raiz de negativo) — nesses casos o candidato round-to-nearest nativo já é `NaN` ou
    /// infinito, e {@link #roundFloat}/{@link #roundDouble} descartam o "exato" sem examiná-lo
    /// (curto-circuito no topo dos dois métodos), então o valor concreto do placeholder nunca é
    /// observado.
    private static final BigDecimal UNREPRESENTABLE_EXACT_PLACEHOLDER = BigDecimal.ZERO;

    /// Valor exato de uma soma/subtração/multiplicação de `double` — sempre exato em
    /// {@link BigDecimal} (sem `MathContext`), nunca perde precisão.
    public static BigDecimal exactAdd(double a, double b) {
        return isFinite(a) && isFinite(b)
                ? new BigDecimal(a).add(new BigDecimal(b))
                : UNREPRESENTABLE_EXACT_PLACEHOLDER;
    }

    /// @see #exactAdd(double, double)
    public static BigDecimal exactSub(double a, double b) {
        return isFinite(a) && isFinite(b)
                ? new BigDecimal(a).subtract(new BigDecimal(b))
                : UNREPRESENTABLE_EXACT_PLACEHOLDER;
    }

    /// @see #exactAdd(double, double)
    public static BigDecimal exactMul(double a, double b) {
        return isFinite(a) && isFinite(b)
                ? new BigDecimal(a).multiply(new BigDecimal(b))
                : UNREPRESENTABLE_EXACT_PLACEHOLDER;
    }

    /// Aproximação de altíssima precisão de `a / b` (ver a classe).
    public static BigDecimal approxDiv(double a, double b) {
        return isFinite(a) && isFinite(b) && b != 0.0
                ? new BigDecimal(a).divide(new BigDecimal(b), EXACT_APPROXIMATION_CONTEXT)
                : UNREPRESENTABLE_EXACT_PLACEHOLDER;
    }

    /// Aproximação de altíssima precisão de `sqrt(a)` (ver a classe).
    public static BigDecimal approxSqrt(double a) {
        return isFinite(a) && a >= 0.0
                ? new BigDecimal(a).sqrt(EXACT_APPROXIMATION_CONTEXT)
                : UNREPRESENTABLE_EXACT_PLACEHOLDER;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /// Traduz o modo de arredondamento (e, para `ROUND_TOWARD_ZERO`, o sinal do valor exato) na
    /// pergunta binária que {@link #roundFloat}/{@link #roundDouble} precisam responder: "o
    /// arredondamento correto é o vizinho representável ≥ exato (rumo a +infinito)?". Para
    /// `ROUND_TOWARD_ZERO`: valor exato ≥0 trunca para baixo (rumo a −infinito, que é rumo a
    /// zero nesse caso); valor exato <0 trunca para cima (rumo a +infinito, que também é rumo a
    /// zero nesse caso).
    private static boolean wantsPlusInfinity(FpRoundingMode mode, boolean exactIsNegative) {
        return switch (mode) {
            case ROUND_TOWARD_PLUS_INFINITY -> true;
            case ROUND_TOWARD_MINUS_INFINITY -> false;
            case ROUND_TOWARD_ZERO -> exactIsNegative;
            case ROUND_TO_NEAREST -> throw new IllegalArgumentException("tratado antes de chamar");
        };
    }
}
