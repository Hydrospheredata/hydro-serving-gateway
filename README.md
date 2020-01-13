# hydro-serving-gateway
|   |   |   |   |
|---|---|---|---|
|[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Hydrospheredata_hydro-serving-gateway&metric=alert_status)](https://sonarcloud.io/dashboard?id=Hydrospheredata_hydro-serving-gateway)|[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=Hydrospheredata_hydro-serving-gateway&metric=vulnerabilities)](https://sonarcloud.io/dashboard?id=Hydrospheredata_hydro-serving-gateway)|[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=Hydrospheredata_hydro-serving-gateway&metric=bugs)](https://sonarcloud.io/dashboard?id=Hydrospheredata_hydro-serving-gateway)|[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=Hydrospheredata_hydro-serving-gateway&metric=code_smells)](https://sonarcloud.io/dashboard?id=Hydrospheredata_hydro-serving-gateway)|
## HTTP API
- POST api/v1/applications/serve/{app_id}/{signature_name}
    
    Serves app with given params. For compatibility with old API.
    
- GET gateway/applications

    Lists all applications.
 
- POST gateway/applications/{app_name}/{signature_name}
   
    Serves app with given params.