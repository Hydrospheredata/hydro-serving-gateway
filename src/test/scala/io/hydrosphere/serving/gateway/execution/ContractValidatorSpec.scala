package io.hydrosphere.serving.gateway.execution

import io.hydrosphere.serving.gateway.{Contract, GenericTest}
import io.hydrosphere.serving.proto.contract.field.ModelField
import io.hydrosphere.serving.proto.contract.tensor.definitions.Shape
import io.hydrosphere.serving.proto.contract.tensor.definitions.{Int32Tensor, Uint8Tensor}
import io.hydrosphere.serving.proto.contract.types.DataType

class ContractValidatorSpec extends GenericTest {

  describe("Contract") {
    it("should pass simple tensor") {
      val a = Int32Tensor(Shape.vector(-1), Seq(1,2,3,4)).toProto
      val b = Uint8Tensor(Shape.vector(3), Seq(1, 2, 3)).toProto
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
          shape = Shape.vector(3).toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_UINT8)
        )
      )

      val result = Contract.validate(request, contract)
      println(result.left.map(_.errors))
      result shouldBe 'right
    }

    it("should pass scalar tensor") {
      val a = Int32Tensor(Shape.scalar, Seq(1)).toProto
      val request = Map(
        "a" -> a,
      )

      val contract = List(
        ModelField(
          name = "a",
          shape = Shape.scalar.toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_INT32)
        ),
      )

      val result = Contract.validate(request, contract)
      println(result.left.map(_.errors))
      result shouldBe 'right
    }

    it("should pass -1 tensor dim") {
      val a = Int32Tensor(Shape.vector(4), Seq(1,2,3,4)).toProto
      val b = Uint8Tensor(Shape.mat(1, 3), Seq(1, 2, 3)).toProto
      val request = Map(
        "a" -> a,
        "b" -> b
      )

      val contract = List(
        ModelField(
          name = "a",
          shape = Shape.vector(-1).toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_INT32)
        ),
        ModelField(
          name = "b",
          shape = Shape.mat(-1, 3).toProto,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_UINT8)
        )
      )

      val result = Contract.validate(request, contract)
      println(result.left.map(_.errors))
      result shouldBe 'right
    }

    it("should detect incompatible tensor shape") {
      val b = Uint8Tensor(Shape.vector(-1), Seq(1, 2, 3)).toProto
      val request = Map(
        "b" -> b
      )
      val contract = List(
        ModelField(
          name = "b",
          shape = Shape.vector(3).toProto,
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
      val b = Uint8Tensor(Shape.vector(3), Seq(1, 2, 3)).toProto
      val request = Map(
        "b" -> b
      )
      val contract = List(
        ModelField(
          name = "b",
          shape = Shape.vector(3).toProto,
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
      val b = Int32Tensor(Shape.vector(3), Seq(1, 2, 3)).toProto
      val request = Map(
        "b" -> b
      )
      val contract = List(
        ModelField(
          name = "b",
          shape = Shape.scalar.toProto,
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
      val b = Int32Tensor(Shape.scalar, Seq(1)).toProto
      val request = Map(
        "b" -> b
      )
      val contract = List(
        ModelField(
          name = "b",
          shape = Shape.vector(3).toProto,
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