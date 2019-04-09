package io.hydrosphere.serving.gateway.service.application

case class ExecutionMeta(
  serviceName: String,
  servicePath: String,
  modelVersionId: Long,
  applicationRequestId: Option[String],
  signatureName: String,
  applicationId: Long,
  stageId: String,
  applicationNamespace: Option[String],
)
