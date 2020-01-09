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

  case class ValidationErrors(errors: NonEmptyList[ContractViolationError]) extends ContractViolationError

  case class MissingField(fieldName: String) extends ContractViolationError

  case class EmptyFieldType(field: ModelField) extends ContractViolationError

  case class InvalidTensorDType(expected: DataType, got: DataType) extends ContractViolationError

  case class UnsupportedDType(got: DataType) extends ContractViolationError

  case object ImpossibleShape extends ContractViolationError

  case class IncompatibleShape(got: Seq[Long], expected: Seq[Long]) extends ContractViolationError

  def validateDataShape(data: TensorProto, expected: TensorShape): ValidatedNec[ContractViolationError, TensorProto] = {
    val x = TensorUtil.verifyShape(data) // TODO(bulat) maybe we should not recalculate shape of our data?
    val maybeDataShape = TensorShape(data.tensorShape) match {
      case TensorShape.AnyDims => ImpossibleShape.asLeft
      case TensorShape.Dims(dims, _) => dims.asRight
    }
    // check actual shape with expected
    expected match {
      case TensorShape.AnyDims => data.validNec
      case TensorShape.Dims(expected, _) =>
        maybeDataShape.flatMap { actual =>
          Either.cond(actual == expected, data, IncompatibleShape(actual, expected)) //TODO(bulat) the check is too strict
        }.toValidatedNec
    }
  }

  def validateDType(data: TensorProto, dtype: DataType, shape: TensorShape): ValidatedNec[ContractViolationError, TensorProto] = {
    if (data.dtype == dtype) {
      validateDataShape(data, shape)
    } else InvalidTensorDType(dtype, data.dtype).invalidNec
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
      case TypeOrSubfields.Dtype(value) => validateDType(data, value, TensorShape(field.shape))
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
