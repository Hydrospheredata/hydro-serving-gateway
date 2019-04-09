package io.hydrosphere.serving.gateway.service.application

case class ExecutionMeta(
  serviceName: String,
  servicePath: String,
  applicationRequestId: Option[String],
  signatureName: String,
  applicationId: Long,
  stageId: String,
  applicationNamespace: Option[String],
)
