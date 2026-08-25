package com.jdoan.inventory.soap.error;

import com.jdoan.inventory.soap.generated.InventoryError;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.SoapFaultDetail;
import org.springframework.ws.soap.server.endpoint.SoapFaultMappingExceptionResolver;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinition;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.namespace.QName;
import javax.xml.transform.Result;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;
import java.util.Properties;

/**
 * Turns Java exceptions into SOAP faults WITH a schema-defined detail body.
 *
 * A SOAP fault has a fixed envelope (faultcode + faultstring), but <detail>
 * is yours. Most tutorials leave it empty; filling it with a type from your
 * own XSD is what makes errors part of the contract instead of an afterthought.
 *
 * The resulting fault looks like:
 *
 *   <SOAP-ENV:Fault>
 *     <faultcode>SOAP-ENV:Client</faultcode>
 *     <faultstring>No product with SKU ELEC-LAP-999</faultstring>
 *     <detail>
 *       <inv:InventoryError xmlns:inv="http://jdoan.com/inventory/v1">
 *         <inv:code>PRODUCT_NOT_FOUND</inv:code>
 *         <inv:message>No product with SKU ELEC-LAP-999</inv:message>
 *         <inv:field>sku</inv:field>
 *         <inv:timestamp>2026-08-24T21:03:11.123-07:00</inv:timestamp>
 *       </inv:InventoryError>
 *     </detail>
 *   </SOAP-ENV:Fault>
 */
@Component
public class InventoryErrorResolver extends SoapFaultMappingExceptionResolver {

    private static final QName FAULT_QNAME =
            new QName("http://jdoan.com/inventory/v1", "InventoryError", "inv");

    public InventoryErrorResolver() {
        // Anything unmapped becomes a Server fault (a genuine 500-equivalent).
        SoapFaultDefinition defaultFault = new SoapFaultDefinition();
        defaultFault.setFaultCode(SoapFaultDefinition.SERVER);
        setDefaultFault(defaultFault);

        // Business errors are the CALLER's fault, so: Client.
        // Getting this distinction right matters - a Client fault tells the
        // consumer "don't retry, fix your request"; Server says "retry later".
        Properties mappings = new Properties();
        mappings.setProperty(NotFoundException.class.getName(), "CLIENT,Requested resource was not found");
        setExceptionMappings(mappings);

        setOrder(1);
    }

    @Override
    protected void customizeFault(Object endpoint, Exception ex, SoapFault fault) {
        InventoryError payload = new InventoryError();
        payload.setMessage(ex.getMessage());

        if (ex instanceof NotFoundException nfe) {
            payload.setCode(nfe.getCode());
            payload.setField(nfe.getField());
        } else {
            payload.setCode("INTERNAL_ERROR");
        }

        try {
            payload.setTimestamp(DatatypeFactory.newInstance()
                    .newXMLGregorianCalendar(GregorianCalendar.from(ZonedDateTime.now())));

            SoapFaultDetail detail = fault.addFaultDetail();
            Result result = detail.getResult();

            Marshaller marshaller = JAXBContext.newInstance(InventoryError.class).createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
            marshaller.marshal(payload, result);
        } catch (Exception marshallingFailure) {
            // Never let error-reporting throw its own error - the caller would
            // get an opaque transport failure instead of a usable fault.
            fault.setFaultActorOrRole("inventory-service");
        }
    }
}
