#!/usr/bin/env sh

[ -z "$JAVA_XMX" ] && JAVA_XMX="256M"

[ -z "$SIDECAR_INGRESS_PORT" ] && SIDECAR_INGRESS_PORT="8080"
[ -z "$SIDECAR_EGRESS_PORT" ] && SIDECAR_EGRESS_PORT="8081"
[ -z "$SIDECAR_HOST" ] && SIDECAR_HOST="sidecar"

[ -z "$GATEWAY_HTTP_PORT" ] && GATEWAY_HTTP_PORT="9090"
[ -z "$GATEWAY_GRPC_PORT" ] && GATEWAY_GRPC_PORT="9091"

[ -z "$APP_SHADOWING_ON" ] && APP_SHADOWING_ON="false"

JAVA_OPTS="-Xmx$JAVA_XMX -Xms$JAVA_XMX"

echo "Running Manager with:"
echo "JAVA_OPTS=$JAVA_OPTS"

if [ "$CUSTOM_CONFIG" = "" ]
then
    echo "Custom config does not exist"
    APP_OPTS="$APP_OPTS -Dsidecar.port=$SIDECAR_INGRESS_PORT -Dsidecar.host=$SIDECAR_HOST"
    APP_OPTS="$APP_OPTS -Dapplication.http-port=$GATEWAY_HTTP_PORT -Dapplication.grpc-port=$GATEWAY_GRPC_PORT"
    APP_OPTS="$APP_OPTS -Dapplication.shadowing-on=$APP_SHADOWING_ON"

    echo "APP_OPTS=$APP_OPTS"

else
   APP_OPTS="$APP_OPTS -Dconfig.file=$CUSTOM_CONFIG"
   echo "with config file config.file=$CUSTOM_CONFIG"
fi

java $JAVA_OPTS $APP_OPTS -cp "/hydro-serving/app/app.jar:/hydro-serving/app/lib/*" io.hydrosphere.serving.gateway.GatewayRuntimeApp