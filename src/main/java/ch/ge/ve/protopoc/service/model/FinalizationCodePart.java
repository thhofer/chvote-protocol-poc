package ch.ge.ve.protopoc.service.model;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Missing javadoc!
 */
public class FinalizationCodePart {
    private final byte[] F;
    private final List<BigInteger> bold_r;

    public FinalizationCodePart(byte[] f, List<BigInteger> bold_r) {
        F = f;
        this.bold_r = bold_r;
    }

    public byte[] getF() {
        return F;
    }

    public List<BigInteger> getBold_r() {
        return bold_r;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FinalizationCodePart that = (FinalizationCodePart) o;
        return Arrays.equals(F, that.F) &&
                Objects.equals(bold_r, that.bold_r);
    }

    @Override
    public int hashCode() {
        return Objects.hash(F, bold_r);
    }
}
