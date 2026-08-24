package com.tj.crypto.autonomy.capital;

/** Unit used for cash, collateral or another fungible asset (for example USD or XAU). */
public record AssetUnit(String code) implements Comparable<AssetUnit> {
    public AssetUnit {
        code = CapitalValues.code(code, "assetUnit", 32);
    }

    @Override
    public int compareTo(AssetUnit other) {
        return code.compareTo(other.code);
    }
}
