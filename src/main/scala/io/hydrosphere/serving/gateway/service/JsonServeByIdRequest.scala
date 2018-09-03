package io.hydrosphere.serving.gateway.service

import spray.json.JsObject

case class JsonServeByIdRequest(
  targetId: Long,
  signatureName: String,
  inputs: JsObject
)