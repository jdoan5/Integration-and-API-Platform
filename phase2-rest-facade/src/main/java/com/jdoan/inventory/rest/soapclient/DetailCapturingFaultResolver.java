package com.jdoan.inventory.rest.soapclient;

import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.core.FaultMessageResolver;
import org.springframework.ws.soap.SoapMessage;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Client-side fault handling.
 *
 * By default WebServiceTemplate throws SoapFaultClientException, whose
 * detail body is awkward to read - Spring-WS's SoapFaultDetailElement exposes
 * getResult() (for WRITING) and addText(), but no getText(). Reading the
 * detail therefore means going back to the raw message.
 *
 * So this resolver serializes the fault message and parses the structured
 * <InventoryError> that Phase 1 puts in <detail>, then throws a plain Java
 * exception carrying the code and field. Everything above stays SOAP-free.
 *
 * This is the payoff of defining faults IN THE SCHEMA: because the provider
 * publishes a typed error, the consumer can branch on a stable code rather
 * than parsing a human-readable sentence that might be reworded tomorrow.
 */
public class DetailCapturingFaultResolver implements FaultMessageResolver {

    @Override
    public void resolveFault(WebServiceMessage message) {
        String faultString = "Upstream SOAP fault";
        String code = null, field = null;

        try {
            if (message instanceof SoapMessage soap && soap.getSoapBody().getFault() != null) {
                faultString = soap.getSoapBody().getFault().getFaultStringOrReason();
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            message.writeTo(buffer);
            String xml = buffer.toString(StandardCharsets.UTF_8);

            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            code  = firstTagValue(document, "code");
            field = firstTagValue(document, "field");

        } catch (Exception parseFailure) {
            // Never let fault PARSING fail the call in a confusing way - fall
            // through and report what we do know.
        }

        throw new UpstreamFaultException(code, field, faultString);
    }

    /** Namespace-agnostic lookup, so a namespace change upstream does not break this. */
    private static String firstTagValue(org.w3c.dom.Document document, String localName) {
        var nodes = document.getElementsByTagNameNS("*", localName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }
}
