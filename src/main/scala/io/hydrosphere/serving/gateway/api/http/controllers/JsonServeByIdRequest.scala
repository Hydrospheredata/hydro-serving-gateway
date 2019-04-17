package io.hydrosphere.serving.gateway.api.http.controllers

import spray.json.JsObject

case class JsonServeByIdRequest(
  targetId: Long,
  inputs: JsObject
)

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
