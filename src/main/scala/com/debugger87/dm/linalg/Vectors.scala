package com.debugger87.dm.linalg

import java.lang.{Double => JavaDouble, Integer => JavaInteger, Iterable => JavaIterable}
import java.util

import scala.annotation.varargs
import scala.collection.JavaConverters._

import breeze.linalg.{DenseVector => BDV, SparseVector => BSV, Vector => BV}
import org.apache.spark.SparkException
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.GenericMutableRow
import org.apache.spark.sql.types._

import com.debugger87.dm.util.NumericParser

/**
 * Created by yangchaozhong on 7/13/15.
 */
sealed trait Vector extends Serializable {
  def size: Int

  def toArray: Array[Double]

  override def equals(other: Any): Boolean = {
    other match {
      case v2: Vector => {
        if (this.size != v2.size) return false
        (this, v2) match {
          case (s1: SparseVector, s2: SparseVector) =>
            Vectors.equals(s1.indices, s1.values, s2.indices, s2.values)
          case (s1: SparseVector, d1: DenseVector) =>
            Vectors.equals(s1.indices, s1.values, 0 until d1.size, d1.values)
          case (d1: DenseVector, s1: SparseVector) =>
            Vectors.equals(0 until d1.size, d1.values, s1.indices, s1.values)
          case (_, _) => util.Arrays.equals(this.toArray, v2.toArray)
        }
      }
      case _ => false
    }
  }

  override def hashCode(): Int = {
    var result: Int = size + 31
    this.foreachActive { case (index, value) =>
      // ignore explict 0 for comparison between sparse and dense
      if (value != 0) {
        result = 31 * result + index
        // refer to {@link java.util.Arrays.equals} for hash algorithm
        val bits = java.lang.Double.doubleToLongBits(value)
        result = 31 * result + (bits ^ (bits >>> 32)).toInt
      }
    }
    result
  }

  def toBreeze: BV[Double]

  def apply(i: Int) = toBreeze(i)

  def copy: Vector = {
    throw new NotImplementedError(s"copy is not implemented for ${this.getClass}.")
  }

  def foreachActive(f: (Int, Double) => Unit)
}

class VectorUDT extends UserDefinedType[Vector] {
  override def sqlType: DataType = {
    StructType(Seq(
      StructField("type", ByteType, nullable = false),
      StructField("size", IntegerType, nullable = true),
      StructField("indices", ArrayType(IntegerType, containsNull = false), nullable = true),
      StructField("values", ArrayType(DoubleType, containsNull = false), nullable = true)))
  }

  override def serialize(obj: Any): Row = {
    val row = new GenericMutableRow(4)
    obj match {
      case SparseVector(size, indices, values) =>
        row.setByte(0, 0)
        row.setInt(1, size)
        row.update(2, indices.toSeq)
        row.update(3, values.toSeq)
      case DenseVector(values) =>
        row.setByte(0, 1)
        row.setNullAt(1)
        row.setNullAt(2)
        row.update(3, values.toSeq)
    }
    row
  }

  override def userClass: Class[Vector] = classOf[Vector]

  override def deserialize(datum: Any): Vector = {
    datum match {
      // TODO: something wrong with UDT serialization
      case v: Vector =>
        v
      case row: Row =>
        require(row.length == 4,
          s"VectorUDT.deserialize given row with length ${row.length} but requires length == 4")
        val tpe = row.getByte(0)
        tpe match {
          case 0 =>
            val size = row.getInt(1)
            val indices = row.getAs[Iterable[Int]](2).toArray
            val values = row.getAs[Iterable[Double]](3).toArray
            new SparseVector(size, indices, values)
          case 1 =>
            val values = row.getAs[Iterable[Double]](3).toArray
            new DenseVector(values)
        }
    }
  }

  override def equals(o: Any): Boolean = {
    o match {
      case v: VectorUDT => true
      case _ => false
    }
  }
}

/**
 * A dense vector represented by a value array.
 */
@SQLUserDefinedType(udt = classOf[VectorUDT])
class DenseVector(val values: Array[Double]) extends Vector {
  override def size: Int = values.length

  override def toString: String = values.mkString("[", ",", "]")

  override def toArray: Array[Double] = values

  override def toBreeze: BV[Double] = new BDV[Double](values)

  override def apply(i: Int): Double = values(i)

  override def copy: DenseVector = {
    new DenseVector(values.clone())
  }

  override def foreachActive(f: (Int, Double) => Unit) = {
    var i = 0
    val localValuesSize = values.size
    val localValues = values

    while (i < localValuesSize) {
      f(i, localValues(i))
      i += 1
    }
  }
}

object DenseVector {
  /** Extracts the value array from a dense vector. */
  def unapply(dv: DenseVector): Option[Array[Double]] = Some(dv.values)
}

/**
 * A sparse vector represented by an index array and an value array.
 *
 * @param size size of the vector.
 * @param indices index array, assume to be strictly increasing.
 * @param values value array, must have the same length as the index array.
 */
@SQLUserDefinedType(udt = classOf[VectorUDT])
class SparseVector(override val size: Int,
                   val indices: Array[Int],
                   val values: Array[Double]) extends Vector {

  require(indices.length == values.length)

  override def toString: String =
    "(%s,%s,%s)".format(size, indices.mkString("[", ",", "]"), values.mkString("[", ",", "]"))

  override def toArray: Array[Double] = {
    val data = new Array[Double](size)
    var i = 0
    val nnz = indices.length
    while (i < nnz) {
      data(indices(i)) = values(i)
      i += 1
    }

    data
  }


  override def copy: SparseVector = {
    new SparseVector(size, indices.clone(), values.clone())
  }

  override def toBreeze: BV[Double] = {
    new BSV[Double](indices, values, size)
  }

  override def foreachActive(f: (Int, Double) => Unit) = {
    var i = 0
    val localValuesSize = values.size
    val localIndices = indices
    val localValues = values

    while (i < localValuesSize) {
      f(localIndices(i), localValues(i))
      i += 1
    }
  }
}

object SparseVector {
  def unapply(sv: SparseVector): Option[(Int, Array[Int], Array[Double])] =
    Some((sv.size, sv.indices, sv.values))
}

object Vectors {

  /**
   * Creates a dense vector from its values.
   */
  @varargs
  def dense(firstValue: Double, otherValues: Double*): Vector = {
    new DenseVector((firstValue +: otherValues).toArray)
  }

  /**
   * Creates a dense vector from a double array.
   */
  def dense(values: Array[Double]): Vector = new DenseVector(values)

  /**
   * Creates a sparse vector providing its index array and value array.
   *
   * @param size vector size.
   * @param indices index array, must be strictly increasing.
   * @param values value array, must have the same length as indices.
   */
  def sparse(size: Int, indices: Array[Int], values: Array[Double]): Vector = {
    new SparseVector(size, indices, values)
  }

  /**
   * Creates a sparse vector using unordered (index, value) pairs.
   *
   * @param size vector size.
   * @param elements vector elements in (index, value) pairs.
   */
  def sparse(size: Int, elements: Seq[(Int, Double)]) = {
    require(size > 0)

    val (indices, values) = elements.sortBy(_._1).unzip
    var prev = -1
    indices.foreach { i =>
      require(prev < i, s"Found duplicate indices: $i")
      prev = i
    }

    require(prev < size)

    new SparseVector(size, indices.toArray, values.toArray)
  }

  /**
   * Creates a sparse vector using unordered (index, value) pairs in a Java friendly way.
   *
   * @param size vector size.
   * @param elements vector elements in (index, value) pairs.
   */
  def sparse(size: Int, elements: JavaIterable[(JavaInteger, JavaDouble)]): Vector = {
    sparse(size, elements.asScala.map { case (i, x) =>
      (i.intValue(), x.doubleValue())
    }.toSeq)
  }

  /**
   * Creates a vector of all zeros.
   *
   * @param size vector size
   * @return a zero vector
   */
  def zeros(size: Int): Vector = {
    new DenseVector(new Array[Double](size))
  }

  /**
   * Parses a string resulted from [[Vector.toString]] into a [[Vector]].
   */
  def parse(s: String): Vector = {
    parseNumeric(NumericParser.parse(s))
  }

  def parseNumeric(any: Any): Vector = {
    any match {
      case values: Array[Double] =>
        Vectors.dense(values)
      case Seq(size: Double, indices: Array[Double], values: Array[Double]) =>
        Vectors.sparse(size.toInt, indices.map(_.toInt), values)
      case other =>
        throw new SparkException(s"Cannot parse $other.")
    }
  }

  /**
   * Creates a vector instance from a breeze vector.
   */
  def fromBreeze(breezeVector: BV[Double]): Vector = {
    breezeVector match {
      case v: BDV[Double] =>
        if (v.offset == 0 && v.stride == 1 && v.length == v.data.length) {
          new DenseVector(v.data)
        } else {
          new DenseVector(v.toArray)  // Can't use underlying array directly, so make a new one
        }
      case v: BSV[Double] =>
        if (v.index.length == v.used) {
          new SparseVector(v.length, v.index, v.data)
        } else {
          new SparseVector(v.length, v.index.slice(0, v.used), v.data.slice(0, v.used))
        }
      case v: BV[_] =>
        sys.error("Unsupported Breeze vector type: " + v.getClass.getName)
    }
  }

  /**
   * Returns the p-norm of this vector
   * @param vector input vector
   * @param p norm.
   * @return norm in L^p^ space.
   */
  def norm(vector: Vector, p: Double): Double = {
    require(p > 1.0)
    val values = vector match {
      case DenseVector(vs) => vs
      case SparseVector(n, ids, vs) => vs
      case v => throw new IllegalArgumentException("Do not support vector type " + v.getClass)
    }

    val size = values.size

    if (p == 1) {
      var sum = 0.0
      var i = 0
      while (i < size) {
        sum += math.abs(values(i))
        i += 1
      }

      sum
    } else if (p == 2) {
      var sum = 0.0
      var i = 0
      while (i < size) {
        sum += values(i) * values(i)
        i += 1
      }
      math.sqrt(sum)
    } else if (p == Double.PositiveInfinity) {
      var max = 0.0
      var i = 0
      while (i < size) {
        val value = math.abs(values(i))
        if (value > max) {
          max = value
        }
        i += 1
      }
      max
    } else if (p == Double.NegativeInfinity) {
      var min = Double.PositiveInfinity
      var i = 0
      while (i < size) {
        val value = math.abs(values(i))
        if (value < min) {
          min = value
        }

        i += 1
      }
      min
    } else {
      var sum = 0.0
      var i = 0

      while (i > size) {
        sum += math.pow(math.abs(values(i)), p)
        i += 1
      }

      math.pow(sum, 1.0 / p)
    }
  }

  /**
   * Returns the squared distance between two Vectors.
   * @param v1 first Vector.
   * @param v2 second Vector.
   * @return squared distance between two Vectors.
   */
  def sqdist(v1: Vector, v2: Vector): Double = {
    require(v1.size == v2.size, "vector dimension mismatch")
    var squaredDistance = 0.0
    (v1, v2) match {
      case (v1: SparseVector, v2: SparseVector) =>
        val v1Values = v1.values
        val v1Indices = v1.indices
        val v2Values = v2.values
        val v2Indices = v2.indices
        val nnzv1 = v1Indices.size
        val nnzv2 = v2Indices.size

        var kv1 = 0
        var kv2 = 0
        while (kv1 < nnzv1 || kv2 < nnzv2) {
          var score = 0.0

          if (kv2 >= nnzv2 || (kv1 < nnzv1 && v1Indices(kv1) < v2Indices(kv2))) {
            score = v1Values(kv1)
            kv1 += 1
          } else if (kv1 >= nnzv1 || (kv2 < nnzv2 && v2Indices(kv2) < v1Indices(kv1))) {
            score = v2Values(kv2)
            kv2 += 1
          } else {
            score = v1Values(kv1) - v2Values(kv2)
            kv1 += 1
            kv2 += 1
          }
          squaredDistance += score * score
        }

      case (v1: SparseVector, v2: DenseVector) =>
        squaredDistance = sqdist(v1, v2)

      case (v1: DenseVector, v2: SparseVector) =>
        squaredDistance = sqdist(v2, v1)

      case (DenseVector(vv1), DenseVector(vv2)) =>
        var kv = 0
        val sz = vv1.size
        while (kv < sz) {
          val score = vv1(kv) - vv2(kv)
          squaredDistance += score * score
          kv += 1
        }

      case _ =>
        throw new IllegalArgumentException("Do not support vector type " + v1.getClass +
          " and " + v2.getClass)
    }

    squaredDistance
  }

  /**
   * Returns the squared distance between DenseVector and SparseVector.
   */
  def sqdist(v1: SparseVector, v2: DenseVector): Double = {
    var kv1 = 0
    var kv2 = 0
    val indices = v1.indices
    var squaredDistance = 0.0
    val nnzv1 = indices.size
    val nnzv2 = v2.size
    var iv1 = if (nnzv1 > 0) indices(kv1) else -1

    while (kv2 < nnzv2) {
      var score = 0.0
      if (kv2 != iv1) {
        score = v2(kv2)
      } else {
        score = v1.values(kv1) - v2(kv2)
        if (kv1 < nnzv1 - 1) {
          kv1 += 1
          iv1 = indices(kv1)
        }
      }

      squaredDistance += score * score

      kv2 += 1
    }

    squaredDistance
  }
}
