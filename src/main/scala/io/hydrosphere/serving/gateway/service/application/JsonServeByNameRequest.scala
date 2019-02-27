package io.hydrosphere.serving.gateway.service.application

import spray.json.JsObject

case class JsonServeByNameRequest(
  appName: String,
  inputs: JsObject
) {
  def toIdRequest(id: Long): JsonServeByIdRequest = {
    JsonServeByIdRequest(
      targetId = id,
      inputs = inputs
    )
  }
}
