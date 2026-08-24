package com.tj.crypto.autonomy.capital;

/** Extensible unit for instrument quantity, such as SHARE, CONTRACT or TROY_OUNCE. */
public record QuantityUnit(String code) implements Comparable<QuantityUnit> {
    public QuantityUnit {
        code = CapitalValues.code(code, "quantityUnit", 64);
    }

    @Override
    public int compareTo(QuantityUnit other) {
        return code.compareTo(other.code);
    }
}
