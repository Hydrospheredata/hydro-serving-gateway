package io.hydrosphere.serving.gateway

import cats.data.{NonEmptyList, ValidatedNec}
import cats.implicits._
import io.hydrosphere.serving.contract.model_field.ModelField
import io.hydrosphere.serving.contract.model_field.ModelField.TypeOrSubfields
import io.hydrosphere.serving.model.api.TensorUtil
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.tensor.TensorProto
import io.hydrosphere.serving.tensorflow.types.DataType

import scala.util.control.NoStackTrace

object Contract {

  sealed abstract class ContractViolationError extends NoStackTrace with Product with Serializable

  case class ValidationErrors(errors: NonEmptyList[ContractViolationError]) extends ContractViolationError {
    override def getMessage: String = {
      val suberrors = errors.map(_.getMessage).toList.mkString("\n")
      s"Multiple validation errors:\n$suberrors"
    }
  }

  case class MissingField(fieldName: String) extends ContractViolationError {
    override def getMessage: String = s"Missing field ${fieldName}"
  }

  case class EmptyFieldType(field: ModelField) extends ContractViolationError {
    override def getMessage: String = s"Empty data type for field ${field}"
  }

  case class InvalidTensorDType(fieldName: String, expected: DataType, got: DataType) extends ContractViolationError {
    override def getMessage: String = s"Expected ${expected} data type for field $fieldName, got ${got}"
  }

  case class UnsupportedDType(fieldName: String, got: DataType) extends ContractViolationError {
    override def getMessage: String = s"Got unsupported data type ${got} for field $fieldName"
  }

  case class ShapelessTensor(fieldName: String) extends ContractViolationError {
    override def getMessage: String = s"Got shapeess tensor for field $fieldName"
  }

  case class IncompatibleShape(fieldName: String, got: Seq[Long], expected: Seq[Long]) extends ContractViolationError {
    override def getMessage: String = s"Expected ${expected} shape for field $fieldName, got ${got}"
  }

  def validateDataShape(name: String, data: TensorProto, expected: TensorShape): ValidatedNec[ContractViolationError, TensorProto] = {
    val maybeDataShape = TensorShape(data.tensorShape) match {
      case TensorShape.AnyDims => ShapelessTensor(name).asLeft
      case TensorShape.Dims(dims, _) => dims.asRight
    }
    // check actual shape with expected
    expected match {
      case TensorShape.AnyDims => data.validNec
      case TensorShape.Dims(expected, _) =>
        maybeDataShape.flatMap { actual =>
          val f = actual.zip(expected).map{
            case (_, -1) => true
            case (a, e) if a != e => false
            case (_, _) => true
          }
          Either.cond(f.nonEmpty && f.reduce(_&&_), data, IncompatibleShape(name, actual, expected))
        }.toValidatedNec
    }
  }

  def validateDType(name: String, data: TensorProto, dtype: DataType, shape: TensorShape): ValidatedNec[ContractViolationError, TensorProto] = {
    if (data.dtype == dtype) {
      validateDataShape(name, data, shape)
    } else InvalidTensorDType(name, dtype, data.dtype).invalidNec
  }

  def validateSubfields(data: TensorProto, subfields: ModelField.Subfield, shape: TensorShape): ValidatedNec[ContractViolationError, TensorProto] = {
    data.mapVal.toList.traverse { sub =>
      validateDataMap(sub.subtensors, subfields.data.toList)
    }.as(data)
  }

  def validateField(data: TensorProto, field: ModelField): ValidatedNec[ContractViolationError, TensorProto] = {
    field.typeOrSubfields match {
      case TypeOrSubfields.Empty => EmptyFieldType(field).invalidNec
      case TypeOrSubfields.Subfields(value) => validateSubfields(data, value, TensorShape(field.shape))
      case TypeOrSubfields.Dtype(value) => validateDType(field.name, data, value, TensorShape(field.shape))
    }
  }

  def validateDataMap(data: Map[String, TensorProto], fields: List[ModelField]): ValidatedNec[ContractViolationError, Map[String, TensorProto]] = {
    val res = fields.traverse { field =>
      data.get(field.name) match {
        case Some(value) => validateField(value, field).map(x => field.name -> x)
        case None => MissingField(field.name).invalidNec
      }
    }
    res.map(x => x.toMap)
  }

  def validate(data: Map[String, TensorProto], fields: List[ModelField]): Either[ValidationErrors, Map[String, TensorProto]] = {
    validateDataMap(data, fields)
      .toEither
      .leftMap(errors => ValidationErrors(errors.toNonEmptyList))
  }
}
