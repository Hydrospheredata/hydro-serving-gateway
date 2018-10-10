# hydro-serving-gateway

## HTTP API
- POST api/v1/applications/serve/{app_id}/{signature_name}
    
    Serves app with given params. For compatibility with old API.
    
- GET gateway/applications

    Lists all applications.
 
- POST gateway/applications/{app_name}/{signature_name}
   
    Serves app with given params.