# odd-transformer
transformer component of the ODD, which serves the purpose to transform the original data-model, as received from the ReflowOS-GraphQL-API, into the shape as required by the ODD.

# installation guide with docker

- mvn clean package

- docker build -t odd_transformer .


_it is highly recommended to only deploy the odd-transformer in combination with the other ODD-components. The respective docker-compose.yml for this purpose can be found in a separate repository._