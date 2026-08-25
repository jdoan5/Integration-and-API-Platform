This XSD is a COPY of the contract published by the SOAP service.

A consumer never shares the provider's source tree - it obtains the contract
(here the XSD; in production, usually the WSDL URL) and generates its own
client stubs from it. That is the whole point of a published contract: the two
sides stay decoupled, sharing only the schema.

Refresh it with:
  curl -s http://localhost:8081/ws/inventory.wsdl -o inventory.wsdl
or re-copy the XSD when the provider publishes a new version.
