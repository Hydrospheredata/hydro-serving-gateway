package io.hydrosphere.serving.gateway.util

import cats.implicits._
import cats.Show
import io.hydrosphere.serving.gateway.execution.Types.MessageData
import io.hydrosphere.serving.gateway.execution.application.AssociatedResponse
import io.hydrosphere.serving.gateway.execution.servable.{ServableRequest, ServableResponse}
import io.hydrosphere.serving.monitoring.metadata.ApplicationInfo
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.TensorShape.{AnyDims, Dims}
import io.hydrosphere.serving.tensorflow.tensor.{TensorProto, TypedTensorFactory}

object ShowInstances {
  implicit def t3[A: Show, B: Show, C: Show] = new Show[(A, B, C)] {
    override def show(t: (A, B, C)): String = s"${t._1.show}, ${t._2.show}, ${t._3.show}"
  }


  implicit val appInfo = new Show[ApplicationInfo] {
    override def show(t: ApplicationInfo): String = t.toString
  }

  implicit val tshape = new Show[TensorShape] {
    override def show(t: TensorShape): String = t match {
      case AnyDims => "any"
      case Dims(dims, _) => "[" + dims.map(_.show).mkString(", ") + "]"
    }
  }

  implicit val tShow = new Show[TensorProto] {
    override def show(t: TensorProto): String = {
      val wrapped = TypedTensorFactory.create(t)
      s"Tensor(type=${wrapped.dtype}, shape=${wrapped.shape.show}, data=${wrapped.data})"
    }
  }

  implicit val tmapShow = new Show[MessageData] {
    override def show(t: MessageData): String = {
      t.map {
        case (k, v) => k.show + ": " + v.show
      }.mkString(", ")
    }
  }

  implicit val reqShow = new Show[ServableRequest] {
    override def show(t: ServableRequest): String = t.data.show
  }

  implicit val respShow = new Show[ServableResponse] {
    override def show(t: ServableResponse): String = t.data match {
      case Left(err) => s"ServableResponse with error(${err.getMessage})"
      case Right(v) => s"ServableResponse(${v.show})"
    }
  }

  implicit val assocResp = new Show[AssociatedResponse] {
    override def show(t: AssociatedResponse): String = s"${t.resp.show} from ${t.servable}"
  }
}