package io.hydrosphere.serving.gateway.service

import spray.json.JsObject

case class JsonServeRequest(
  targetId: Long,
  signatureName: String,
  inputs: JsObject
)
