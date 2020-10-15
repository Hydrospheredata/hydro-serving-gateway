package io.hydrosphere.serving.gateway.util.proto_json

import io.hydrosphere.serving.contract.model_field.ModelField
import io.hydrosphere.serving.model.api.ValidationError
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.TensorShape.{AnyDims, Dims}
import io.hydrosphere.serving.tensorflow.tensor._
import io.hydrosphere.serving.tensorflow.types.DataType
import spray.json._


class InfoFieldBuilder(val field: ModelField, val dataType: DataType) {

  def convert(data: JsValue): Either[ValidationError, TypedTensor[_]] = {
    data match {
      // collection
      case JsArray(elements) => process(elements)
      // scalar
      case str: JsString => process(Seq(str))
      case num: JsNumber => process(Seq(num))
      case bool: JsBoolean => process(Seq(bool))
      // invalid
      case _ => Left(ValidationError.IncompatibleFieldTypeError(field.name, dataType))
    }
  }

  def process(data: Seq[JsValue]): Either[ValidationError, TypedTensor[_]] = {
    val flatData = field.shape match {
      case Some(_) => flatten(data)
      case None => data
    }
    val factory = TypedTensorFactory(dataType)

    verifyShape(flatData).flatMap { reshapedData =>
      val convertedData = factory match {
        case FloatTensor | SComplexTensor => reshapedData.map(_.asInstanceOf[JsNumber].value.floatValue)
        case DoubleTensor | DComplexTensor => reshapedData.map(_.asInstanceOf[JsNumber].value.doubleValue)
        case Uint64Tensor | Int64Tensor => reshapedData.map(_.asInstanceOf[JsNumber].value.longValue)
        case Int8Tensor | Uint8Tensor |
             Int16Tensor | Uint16Tensor |
             Int32Tensor | Uint32Tensor => reshapedData.map(_.asInstanceOf[JsNumber].value.intValue)
        case StringTensor => reshapedData.map(_.asInstanceOf[JsString].value)
        case BoolTensor => reshapedData.map(_.asInstanceOf[JsBoolean].value)
      }
      toTensor(factory, convertedData)
    }
  }

  def verifyShape(data: Seq[JsValue]): Either[ValidationError.IncompatibleFieldShapeError, Seq[JsValue]] = {
    TensorShape(field.shape) match {
      case AnyDims => Right(data)
      case Dims(tensorDims, _) if tensorDims.isEmpty && data.length == 1 => Right(data)
      case Dims(tensorDims, _) if tensorDims.isEmpty && data.length != 1 => Left(ValidationError.IncompatibleFieldShapeError(field.name, field.shape))
      case Dims(tensorDims, _) =>
        val reverseTensorDimIter = tensorDims.reverseIterator

        val actualDims = Array.fill(tensorDims.length)(0L)
        var actualDimId = actualDims.indices.last
        var dimLen = data.length

        var isShapeOk = true

        while (isShapeOk && reverseTensorDimIter.hasNext) {
          val currentDim = reverseTensorDimIter.next()
          val subCount = dimLen.toDouble / currentDim.toDouble
          if (subCount.isWhole) { // ok
            dimLen = subCount.toInt
            if (subCount < 0) {
              actualDims(actualDimId) = dimLen.abs
            } else {
              actualDims(actualDimId) = currentDim
            }
            actualDimId -= 1
          } else { // not ok
            isShapeOk = false
          }
        }

        if (isShapeOk) {
          Right(data)
        } else {
          Left(ValidationError.IncompatibleFieldShapeError(field.name, field.shape))
        }
    }
  }

  def toTensor[T <: TypedTensor[_]](factory: TypedTensorFactory[T], flatData: Seq[Any]): Either[ValidationError, T] = {
    factory.createFromAny(flatData, TensorShape(field.shape)) match {
      case Some(tensor) => Right(tensor)
      case None => Left(ValidationError.IncompatibleFieldTypeError(field.name, dataType))
    }
  }

  private def flatten(arr: Seq[JsValue]): Seq[JsValue] = {
    arr.flatMap {
      case arr: JsArray => flatten(arr.elements)
      case value => List(value)
    }
  }

}