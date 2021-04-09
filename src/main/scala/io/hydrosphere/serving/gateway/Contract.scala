package io.hydrosphere.serving.gateway

import cats.data.{NonEmptyList, ValidatedNec}
import cats.implicits._
import io.hydrosphere.serving.proto.contract.field.ModelField
import io.hydrosphere.serving.proto.contract.field.ModelField.TypeOrSubfields
import io.hydrosphere.serving.proto.contract.tensor.TensorShape
import io.hydrosphere.serving.proto.contract.tensor.definitions.Shape
import io.hydrosphere.serving.proto.contract.tensor.Tensor
import io.hydrosphere.serving.proto.contract.types.DataType

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

  def validateDataShape(name: String, data: Tensor, expected: Shape): ValidatedNec[ContractViolationError, Tensor] = {
    val maybeDataShape = Shape(data.tensorShape) match {
      case Shape.AnyShape => ShapelessTensor(name).asLeft
      case Shape.LocalShape(dims) => dims.asRight
    }
    // check actual shape with expected
    expected match {
      case Shape.AnyShape => data.validNec
      case Shape.LocalShape(dims) =>
        maybeDataShape.flatMap { actual =>
          if (dims.isEmpty && actual.isEmpty) {
            Right(data)
          } else {
            val f = actual.zip(dims).map{
              case (_, -1) => true
              case (a, e) if a != e => false
              case (_, _) => true
            }
            val isTensor = f.nonEmpty && f.reduce(_&&_)
            Either.cond(isTensor, data, IncompatibleShape(name, actual, dims))
          }
        }.toValidatedNec
    }
  }

  def validateDType(name: String, data: Tensor, dtype: DataType, shape: Shape): ValidatedNec[ContractViolationError, Tensor] = {
    if (data.dtype == dtype) {
      validateDataShape(name, data, shape)
    } else InvalidTensorDType(name, dtype, data.dtype).invalidNec
  }

  def validateSubfields(data: Tensor, subfields: ModelField.Subfield): ValidatedNec[ContractViolationError, Tensor] = {
    data.mapVal.toList.traverse { sub =>
      validateDataMap(sub.subtensors, subfields.data.toList)
    }.as(data)
  }

  def validateField(data: Tensor, field: ModelField): ValidatedNec[ContractViolationError, Tensor] = {
    field.typeOrSubfields match {
      case TypeOrSubfields.Empty => EmptyFieldType(field).invalidNec
      case TypeOrSubfields.Subfields(value) => validateSubfields(data, value)
      case TypeOrSubfields.Dtype(value) => validateDType(field.name, data, value, Shape(field.shape))
    }
  }

  def validateDataMap(data: Map[String, Tensor], fields: List[ModelField]): ValidatedNec[ContractViolationError, Map[String, Tensor]] = {
    val res = fields.traverse { field =>
      data.get(field.name) match {
        case Some(value) => validateField(value, field).map(x => field.name -> x)
        case None => MissingField(field.name).invalidNec
      }
    }
    res.map(x => x.toMap)
  }

  def validate(data: Map[String, Tensor], fields: List[ModelField]): Either[ValidationErrors, Map[String, Tensor]] = {
    validateDataMap(data, fields)
      .toEither
      .leftMap(errors => ValidationErrors(errors.toNonEmptyList))
  }
}
