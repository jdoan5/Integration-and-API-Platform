package com.jdoan.inventory.graphql;

import com.jdoan.inventory.graphql.api.WarehouseCodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The hyphen that cannot cross into a GraphQL enum.
 *
 * Small enough to look pointless, until a round trip drops a character and every
 * warehouse filter silently matches nothing.
 */
class WarehouseCodesTest {

    @Test
    void translatesToTheDomainForm() {
        assertEquals("WH-EAST", WarehouseCodes.toDomain("WH_EAST"));
    }

    @Test
    void translatesToTheGraphqlForm() {
        assertEquals("WH_EAST", WarehouseCodes.toGraphql("WH-EAST"));
    }

    @Test
    void roundTripsEveryRealWarehouse() {
        for (String code : new String[]{"WH-WEST", "WH-CENT", "WH-EAST"}) {
            assertEquals(code, WarehouseCodes.toDomain(WarehouseCodes.toGraphql(code)));
        }
    }

    @Test
    void nullSurvives() {
        // An omitted optional argument arrives as null and must stay null -
        // turning it into "" would filter by a warehouse that does not exist
        // instead of not filtering at all.
        assertNull(WarehouseCodes.toDomain(null));
        assertNull(WarehouseCodes.toGraphql(null));
    }
}
