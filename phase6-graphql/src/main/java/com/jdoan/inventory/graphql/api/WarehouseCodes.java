package com.jdoan.inventory.graphql.api;

/**
 * Translates between the domain's warehouse codes and the GraphQL enum.
 *
 * WHY THIS EXISTS: a GraphQL enum value must match /[_A-Za-z][_0-9A-Za-z]*!/,
 * so the real code WH-EAST is not a legal enum value. The hyphen the XSD chose
 * years ago cannot cross into this schema.
 *
 * The alternative was to type the field as String and lose the closed value set
 * - which is exactly the guarantee this project spends four phases defending.
 * Paying a mapping function to keep the enum is the right trade, but it is a
 * real cost of moving a contract between protocols, not a free translation.
 */
public final class WarehouseCodes {

    private WarehouseCodes() {}

    /** WH_EAST -> WH-EAST. Null-safe: an omitted optional argument stays null. */
    public static String toDomain(String graphqlEnum) {
        return graphqlEnum == null ? null : graphqlEnum.replace('_', '-');
    }

    /** WH-EAST -> WH_EAST. */
    public static String toGraphql(String domainCode) {
        return domainCode == null ? null : domainCode.replace('-', '_');
    }
}
