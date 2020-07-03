package io.hydrosphere.serving.gateway.execution

import io.hydrosphere.serving.contract.model_field.ModelField
import io.hydrosphere.serving.gateway.{Contract, GenericTest}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.tensor.{Int32Tensor, Uint8Tensor}
import io.hydrosphere.serving.tensorflow.types.DataType

class ContractValidatorSpec extends GenericTest {

  describe("Contract") {
    it("should pass simple tensor") {
      val a = Int32Tensor(TensorShape.vector(-1), Seq(1,2,3,4)).toProto
      val b = Uint8Tensor(TensorShape.vector(3), Seq(1, 2, 3)).toProto
      val request = Map(
        "a" -> a,
        "b" -> b
      )

      val contract = List(
        ModelField(
          name = "a",
          shape = None,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_INT32)
        ),
        ModelField(
          name = "b",
          shape = TensorShape.vector(3).toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_UINT8)
        )
      )

      val result = Contract.validate(request, contract)
      println(result.left.map(_.errors))
      result shouldBe 'right
    }

    it("should pass -1 tensor dim") {
      val a = Int32Tensor(TensorShape.vector(4), Seq(1,2,3,4)).toProto
      val b = Uint8Tensor(TensorShape.mat(1, 3), Seq(1, 2, 3)).toProto
      val request = Map(
        "a" -> a,
        "b" -> b
      )

      val contract = List(
        ModelField(
          name = "a",
          shape = TensorShape.vector(-1).toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_INT32)
        ),
        ModelField(
          name = "b",
          shape = TensorShape.mat(-1, 3).toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_UINT8)
        )
      )

      val result = Contract.validate(request, contract)
      println(result.left.map(_.errors))
      result shouldBe 'right
    }

    it("should detect incompatible tensor shape") {
      val b = Uint8Tensor(TensorShape.vector(-1), Seq(1, 2, 3)).toProto
      val request = Map(
        "b" -> b
      )
      val contract = List(
        ModelField(
          name = "b",
          shape = TensorShape.vector(3).toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_UINT8)
        )
      )

      val result = Contract.validate(request, contract)
      val output = result.left.get.errors.collect{
        case Contract.IncompatibleShape(name, got, expected) => s"$name $got != $expected"
      }.mkString
      println(output)
      result shouldBe 'left
      assert(result.left.get.errors.exists(_.isInstanceOf[Contract.IncompatibleShape]))
    }

    it("should detect incompatible tensor dtype") {
      val b = Uint8Tensor(TensorShape.vector(3), Seq(1, 2, 3)).toProto
      val request = Map(
        "b" -> b
      )
      val contract = List(
        ModelField(
          name = "b",
          shape = TensorShape.vector(3).toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_INT32)
        )
      )

      val result = Contract.validate(request, contract)
      val output = result.left.get.errors.collect{
        case Contract.InvalidTensorDType(name, got, expected) => s"$name $got != $expected"
      }.mkString
      println(output)
      result shouldBe 'left
      assert(result.left.get.errors.exists(_.isInstanceOf[Contract.InvalidTensorDType]))
    }

    it("should prevent vector data in scalar field") {
      val b = Int32Tensor(TensorShape.vector(3), Seq(1, 2, 3)).toProto
      val request = Map(
        "b" -> b
      )
      val contract = List(
        ModelField(
          name = "b",
          shape = TensorShape.scalar.toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_INT32)
        )
      )

      val result = Contract.validate(request, contract)
      val output = result.left.get.errors.collect{
        case Contract.IncompatibleShape(name, got, expected) => s"$name $got != $expected"
      }.mkString
      println(output)
      result shouldBe 'left
      assert(result.left.get.errors.exists(_.isInstanceOf[Contract.IncompatibleShape]))
    }

    it("should prevent scalar data in vector field") {
      val b = Int32Tensor(TensorShape.scalar, Seq(1)).toProto
      val request = Map(
        "b" -> b
      )
      val contract = List(
        ModelField(
          name = "b",
          shape = TensorShape.vector(3).toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_INT32)
        )
      )

      val result = Contract.validate(request, contract)
      val output = result.left.get.errors.collect{
        case Contract.IncompatibleShape(name, got, expected) => s"$name $got != $expected"
      }.mkString
      println(output)
      result shouldBe 'left
      assert(result.left.get.errors.exists(_.isInstanceOf[Contract.IncompatibleShape]))
    }
  }
}