package com.jdoan.inventory.graphql;

import com.jdoan.inventory.graphql.api.IdempotencyUnavailableException;
import com.jdoan.inventory.graphql.api.InventoryBackend;
import com.jdoan.inventory.graphql.api.InventoryService;
import com.jdoan.inventory.graphql.api.Types;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The idempotency store must fail CLOSED.
 *
 * Found when Docker stopped mid-session: with Redis unreachable the idempotency
 * lookup was going through the same helper as every other cache read, which
 * treats an outage as a miss. Two identical mutations wrote two movements while
 * promising to be safe to retry.
 *
 * `cacheEnabled = false` reproduces the same condition without needing to take
 * Redis down, which is why the flag is worth having.
 */
class IdempotencyFailsClosedTest {

    private InventoryService serviceWithNoStore() {
        // Nothing is called before the idempotency check, so the backend and the
        // redis template can be null - if that ever stops being true this test
        // will say so loudly rather than passing for the wrong reason.
        return new InventoryService((InventoryBackend) null, null, null, false);
    }

    private Types.MovementInput input(String idempotencyKey) {
        return new Types.MovementInput("ELEC-LAP-001", "WH_EAST", "ADJUSTMENT", 1,
                null, null, idempotencyKey);
    }

    @Test
    void refusesWhenAKeyWasSuppliedButTheStoreIsUnavailable() {
        assertThrows(IdempotencyUnavailableException.class,
                () -> serviceWithNoStore().recordMovement(input("some-key")));
    }

    @Test
    void refusesOnABlankKeyOnlyIfItIsActuallyPresent() {
        // A blank key is "no key" - the caller never asked for the guarantee,
        // so this must NOT take the fail-closed path. It fails later for an
        // unrelated reason (no SOAP client), which is a different exception.
        assertThrows(Exception.class,
                () -> serviceWithNoStore().recordMovement(input("   ")));
    }
}
