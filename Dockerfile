FROM maven:3-jdk-11
COPY NRGRSynapseGlue/pom.xml /
COPY NRGRSynapseGlue/src /src
RUN mvn clean install
CMD exec mvn exec:java -DentryPoint=org.sagebionetworks.NRGRSynapseGlue
