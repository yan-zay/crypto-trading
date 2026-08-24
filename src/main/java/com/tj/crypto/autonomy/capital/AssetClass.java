package com.tj.crypto.autonomy.capital;

/** Extensible asset-class identifier; no closed crypto-specific enum is imposed. */
public record AssetClass(String code) implements Comparable<AssetClass> {
    public AssetClass {
        code = CapitalValues.code(code, "assetClass", 64);
    }

    @Override
    public int compareTo(AssetClass other) {
        return code.compareTo(other.code);
    }
}
