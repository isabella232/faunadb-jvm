package faunadb.values

import com.fasterxml.jackson.annotation.{ JsonIgnore, JsonProperty, JsonValue }
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.NullNode
import faunadb.jackson._
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{ Instant, LocalDate }
import scala.annotation.meta.{ field, getter, param }

/**
  * A FaunaDB value.
  *
  * '''Reference''': [[https://faunadb.com/documentation/queries#values FaunaDB Values]]
  *
  * ===Overview===
  *
  * Value is an algebraic data type that represents the value of
  * possible FaunaDB query responses. While it is possible to extract
  * data out of a Value object using pattern matching, the
  * [[faunadb.values.Field]] lets you create more complex and reusable
  * data extractors.
  *
  * {{{
  * // Simple, adhoc extraction:
  * value("data", "name").to[String].get
  *
  * // Using a reusable Field:
  * val NameField = Field("data", "name").to[String]
  * value(NameField).get
  * }}}
  *
  * Extraction can be composed:
  *
  * {{{
  * val refAndNameAndAge = for {
  *   ref <- value("ref").to[RefV]
  *   name <- value("data", "name").to[String]
  *   age <- value("data", "age").to[Int]
  * } yield (ref, name, age)
  *
  * refAndNameAndAge.get
  *
  * // or
  *
  * val RefAndNameAndAgeField = Field.zip(
  *   Field("ref").to[RefV],
  *   Field("data", "name").to[String],
  *   Field("data", "age").to[Int])
  *
  * value(RefAndNameAndAgeField).get
  * }}}
  *
  * If a value may be an array, or may contain an array, the array's
  * elements may be cast using [[collect]]:
  *
  * {{{
  * value("data", "tags").collect(Field.to[String]).get
  *
  * // or
  *
  * val TagsField = Field("data", "tags").collect(Field.to[String])
  * value(TagsField).get
  * }}}
  */
@JsonDeserialize(using=classOf[ValueDeserializer])
sealed trait Value {

  /** Extract a value with the provided field. */
  final def apply[T](field: Field[T]): Result[T] = field.get(this)

  /** Extract the sub-value at the specified path. */
  final def apply(p: FieldPath, ps: FieldPath*): Result[Value] = apply(Field(p, ps: _*))

  /** Cast the value to T, using a Decoder. */
  final def to[T: Decoder]: Result[T] = apply(Field.to[T])

  /** Extract the elements of an ArrayV using the provided field. */
  final def collect[T](field: Field[T]): Result[Seq[T]] = apply(Field.collect(field))
}

/** Companion object to the Value trait. */
object Value {

  /** Create a string value. */
  def apply(str: String): Value = StringV(str)

  /** Create a long value. */
  def apply(long: Long): Value = LongV(long)

  /** Create a double value. */
  def apply(double: Double): Value = DoubleV(double)

  /** Create a boolean value. */
  def apply(boolean: Boolean): Value = BooleanV(boolean)

  /** Create a timestamp value. */
  def apply(instant: Instant): Value = TimeV(instant)

  /** Create a date value. */
  def apply(localdate: LocalDate): Value = DateV(localdate)
}

// Concrete Value types

/**
  * Base trait for all scalar values.
  *
  * Arrays, objects, and null are not considered scalar values.
  */
sealed trait ScalarValue extends Value

/** A String value. */
case class StringV(@(JsonValue @getter) value: String) extends ScalarValue

/** A Long value. */
case class LongV(@(JsonValue @getter) value: Long) extends ScalarValue

/** A Double value. */
case class DoubleV(@(JsonValue @getter) value: Double) extends ScalarValue

/** A Boolean value. */
sealed abstract class BooleanV(@(JsonValue @getter) val value: Boolean) extends ScalarValue {
  // satisfy name-based extractor interface
  val isEmpty = false
  val get = value
}
case object TrueV extends BooleanV(true)
case object FalseV extends BooleanV(false)

object BooleanV {
  def apply(b: Boolean) = if (b) TrueV else FalseV
  def unapply(b: BooleanV) = b
}

// Fauna special types

/** A Ref. */
case class RefV(@(JsonProperty @field @param)("@ref") value: String) extends ScalarValue
object RefV {
  def apply(clss: RefV, id: String): RefV = RefV(s"${clss.value}/$id")
}

/** A Set Ref. */
case class SetRefV(@JsonProperty("@set") parameters: Value) extends ScalarValue

/** A Timestamp value. */
case class TimeV(@(JsonIgnore @param @field @getter) instant: Instant) extends ScalarValue {
  @JsonProperty("@ts")
  val strValue = instant.toString(ISODateTimeFormat.dateTimeNoMillis())
}
object TimeV {
  def apply(value: String): TimeV =
    TimeV(ISODateTimeFormat.dateTimeNoMillis().parseDateTime(value).toInstant)
}

/** A Date value. */
case class DateV(@(JsonIgnore @param @field @getter) localDate: LocalDate) extends ScalarValue {
  @JsonProperty("@date")
  val strValue = localDate.toString
}
object DateV {
  def apply(value: String): DateV = DateV(LocalDate.parse(value))
}

// Container types and Null

/** An Object value. */
case class ObjectV(@(JsonValue @getter) fields: Map[String, Value]) extends Value
object ObjectV {
  val empty = ObjectV()
  def apply(fields: (String, Value)*) = new ObjectV(fields.toMap)
}

/** An Array. */
case class ArrayV(@(JsonValue @getter) elems: Vector[Value]) extends Value
object ArrayV {
  val empty = ArrayV()
  def apply(elems: Value*) = new ArrayV(Vector(elems: _*))
}

/** The Null value. */
sealed trait NullV extends Value
case object NullV extends NullV {
  @(JsonValue @getter) val value = NullNode.instance
}
