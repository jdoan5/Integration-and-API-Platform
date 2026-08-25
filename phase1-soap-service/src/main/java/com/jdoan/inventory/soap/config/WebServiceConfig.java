package com.jdoan.inventory.soap.config;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

import java.util.List;

/**
 * Spring-WS wiring. Three things happen here:
 *
 *   1. A servlet is mounted at /ws/* to receive SOAP requests.
 *   2. The WSDL is GENERATED from inventory-v1.xsd at runtime and published
 *      at /ws/inventory.wsdl - you never hand-write or check in a WSDL.
 *   3. A validating interceptor rejects any request that violates the schema
 *      BEFORE it reaches your endpoint code.
 *
 * Point 3 is the payoff of contract-first: constraints like the SKU regex and
 * the movement-type enumeration are enforced at the boundary, for free.
 */
@EnableWs
@Configuration
public class WebServiceConfig implements WsConfigurer {

    /** Mounts the SOAP servlet. setTransformWsdlLocations rewrites the WSDL's
     *  soap:address to match the host it was fetched from - important later
     *  when Kong fronts this service on a different port. */
    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(ApplicationContext ctx) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(ctx);
        servlet.setTransformWsdlLocations(true);
        return new ServletRegistrationBean<>(servlet, "/ws/*");
    }

    /** The XSD, loaded from the classpath. Single source of truth. */
    @Bean
    public XsdSchema inventorySchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/inventory-v1.xsd"));
    }

    /**
     * The bean NAME becomes the WSDL filename: bean "inventory" is served at
     * /ws/inventory.wsdl.
     *
     * portTypeName/locationUri/targetNamespace are the three knobs that shape
     * the generated WSDL. The request/response suffix convention is how
     * Spring-WS pairs XSD elements into WSDL operations: GetProductRequest +
     * GetProductResponse become the "GetProduct" operation automatically.
     */
    @Bean(name = "inventory")
    public DefaultWsdl11Definition inventoryWsdl11Definition(XsdSchema inventorySchema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("InventoryPort");
        wsdl.setLocationUri("/ws");
        wsdl.setTargetNamespace("http://jdoan.com/inventory/v1");
        wsdl.setSchema(inventorySchema);
        wsdl.setRequestSuffix("Request");
        wsdl.setResponseSuffix("Response");
        return wsdl;
    }

    /**
     * Schema validation on the way IN. Turn validateResponse on too and the
     * service also refuses to emit anything that breaks its own contract -
     * a surprisingly effective way to catch bugs during development.
     */
    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        PayloadValidatingInterceptor validating = new PayloadValidatingInterceptor();
        validating.setXsdSchema(inventorySchema());
        validating.setValidateRequest(true);
        validating.setValidateResponse(true);
        interceptors.add(validating);
    }
}
