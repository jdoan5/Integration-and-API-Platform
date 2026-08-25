package com.jdoan.inventory.soap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT TESTS.
 *
 * These assert things CONSUMERS depend on. If one fails, you have made a
 * breaking change - the build should stop before anyone's integration does.
 *
 * This is the discipline that makes "contract-first" more than a slogan: the
 * contract is protected by tests, not by everyone remembering to be careful.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractTest {

    @LocalServerPort
    int port;

    /** Fetches the live WSDL the same way a consumer's tooling would. */
    private String wsdl() {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ws/inventory.wsdl"))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("Could not fetch WSDL", e);
        }
    }

    @Test
    @DisplayName("WSDL is published and well-formed")
    void wsdlIsPublished() {
        String wsdl = wsdl();
        assertThat(wsdl).isNotNull();
        assertThat(wsdl).contains("<wsdl:definitions");
        assertThat(wsdl).contains("targetNamespace=\"http://jdoan.com/inventory/v1\"");
    }

    @Test
    @DisplayName("all four operations are exposed - removing one breaks consumers")
    void allOperationsPresent() {
        String wsdl = wsdl();
        assertThat(wsdl)
                .contains("<wsdl:operation name=\"GetProduct\"")
                .contains("<wsdl:operation name=\"GetStockLevel\"")
                .contains("<wsdl:operation name=\"ListLowStock\"")
                .contains("<wsdl:operation name=\"RecordStockMovement\"");
    }

    @Test
    @DisplayName("every operation has an input AND an output message")
    void operationsAreCallable() {
        String wsdl = wsdl();
        // Checking operation NAMES alone is not enough. Misconfigure
        // requestSuffix on the WSDL bean and the names survive (they get
        // derived from the *Response elements) while every wsdl:input
        // disappears - leaving a contract nobody can actually call.
        // Both counts are 8: 4 operations x (portType + binding).
        assertThat(countOccurrences(wsdl, "<wsdl:input"))
                .as("4 operations must each declare an input, in portType and binding")
                .isEqualTo(8);
        assertThat(countOccurrences(wsdl, "<wsdl:output"))
                .as("4 operations must each declare an output, in portType and binding")
                .isEqualTo(8);
    }

    private static int countOccurrences(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @DisplayName("no phantom operations - the InventoryError element must not create one")
    void noSpuriousOperations() {
        String wsdl = wsdl();
        // Regression guard: an element ending in "Fault" made Spring-WS invent
        // an "Inventory" operation. Renaming it to InventoryError fixed that.
        assertThat(wsdl).doesNotContain("<wsdl:operation name=\"Inventory\"");
        assertThat(wsdl.split("<wsdl:operation name=").length - 1)
                .as("operations appear in both portType and binding, so 4 ops = 8 mentions")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("validation rules are published to consumers, not just enforced server-side")
    void schemaConstraintsAreInTheContract() {
        String wsdl = wsdl();
        assertThat(wsdl)
                .as("SKU pattern must be visible to consumers")
                .contains("[A-Z]{3,4}-[A-Z0-9]{3,5}-?[0-9]{0,5}");
        assertThat(wsdl)
                .as("movement types are a closed set")
                .contains("<xs:enumeration value=\"TRANSFER_IN\"/>");
        assertThat(wsdl)
                .as("quantity must be positive")
                .contains("<xs:minExclusive value=\"0\"/>");
    }

    @Test
    @DisplayName("the error type stays in the schema so consumers can parse fault details")
    void errorTypeIsPartOfTheContract() {
        assertThat(wsdl()).contains("<xs:element name=\"InventoryError\">");
    }
}
