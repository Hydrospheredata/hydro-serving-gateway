package io.hydrosphere.serving.gateway.service.application

import spray.json.JsObject

case class JsonServeByIdRequest(
  targetId: Long,
  inputs: JsObject
)
