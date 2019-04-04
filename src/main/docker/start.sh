#!/usr/bin/env sh

[ -z "$JAVA_XMX" ] && JAVA_XMX="256M"

[ -z "$APP_SHADOWING_ON" ] && APP_SHADOWING_ON="false"

[ -z "$MAX_CONTENT_LENGTH" ] && MAX_CONTENT_LENGTH="536870912"
[ -z "$MAX_MESSAGE_SIZE" ] && MAX_MESSAGE_SIZE="536870912"
[ -z "$GRPC_DEADLINE" ] && GRPC_DEADLINE="60seconds"

[ -z "$API_GATEWAY_HOST" ] && API_GATEWAY_HOST="managerui"
[ -z "$API_GATEWAY_GRPC_PORT" ] && API_GATEWAY_GRPC_PORT="9091"
[ -z "$API_GATEWAY_HTTP_PORT" ] && API_GATEWAY_HTTP_PORT="9090"

[ -z "$GRPC_PORT" ] && GRPC_PORT="9091"
[ -z "$HTTP_PORT" ] && HTTP_PORT="9090"

JAVA_OPTS="-Xmx$JAVA_XMX -Xms$JAVA_XMX"

echo "Running Manager with:"
echo "JAVA_OPTS=$JAVA_OPTS"

if [ "$CUSTOM_CONFIG" = "" ]
then
    echo "Custom config does not exist"
    APP_OPTS="$APP_OPTS -Dapplication.http.port=$HTTP_PORT -Dapplication.grpc.port=$GRPC_PORT"
    APP_OPTS="$APP_OPTS -Dapplication.shadowing-on=$APP_SHADOWING_ON"
    APP_OPTS="$APP_OPTS -Dakka.http.server.parsing.max-content-length=$MAX_CONTENT_LENGTH -Dakka.http.client.parsing.max-content-length=$MAX_CONTENT_LENGTH"
    APP_OPTS="$APP_OPTS -Dapplication.grpc.deadline=$GRPC_DEADLINE -Dapplication.grpc.max-message-size=$MAX_MESSAGE_SIZE"
    APP_OPTS="$APP_OPTS -Dapplication.api-gateway.host=$API_GATEWAY_HOST -Dapplication.api-gateway.grpc-port=$API_GATEWAY_GRPC_PORT -Dapplication.api-gateway.http-port=$API_GATEWAY_HTTP_PORT"
    echo "APP_OPTS=$APP_OPTS"

else
   APP_OPTS="$APP_OPTS -Dconfig.file=$CUSTOM_CONFIG"
   echo "with config file config.file=$CUSTOM_CONFIG"
fi

java $JAVA_OPTS $APP_OPTS -cp "/hydro-serving/app/app.jar:/hydro-serving/app/lib/*" io.hydrosphere.serving.gateway.Main
