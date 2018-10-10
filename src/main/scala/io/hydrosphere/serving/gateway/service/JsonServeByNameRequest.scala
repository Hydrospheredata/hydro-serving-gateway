package io.hydrosphere.serving.gateway.service

import spray.json.JsObject

case class JsonServeByNameRequest(
  appName: String,
  signatureName: String,
  inputs: JsObject
) {
  def toIdRequest(id: Long): JsonServeByIdRequest = {
    JsonServeByIdRequest(
      targetId = id,
      signatureName = signatureName,
      inputs = inputs
    )
  }
}
