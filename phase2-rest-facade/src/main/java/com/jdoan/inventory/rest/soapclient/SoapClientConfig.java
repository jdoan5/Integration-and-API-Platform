package com.jdoan.inventory.rest.soapclient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;

/**
 * Client side of the contract.
 *
 * The marshaller is pointed at the package of stubs generated from the
 * provider's XSD. Nothing here imports anything from phase1-soap-service -
 * the two modules share a SCHEMA, not code. That is the decoupling a
 * published contract buys you.
 */
@Configuration
public class SoapClientConfig {

    @Bean
    public Jaxb2Marshaller inventoryMarshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("com.jdoan.inventory.rest.soapclient.generated");
        return marshaller;
    }

    @Bean
    public WebServiceTemplate inventoryWebServiceTemplate(
            Jaxb2Marshaller marshaller,
            @Value("${inventory.soap.uri}") String defaultUri) {

        WebServiceTemplate template = new WebServiceTemplate();
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setDefaultUri(defaultUri);
        // Parse structured fault details into plain Java exceptions.
        template.setFaultMessageResolver(new DetailCapturingFaultResolver());
        return template;
    }
}
