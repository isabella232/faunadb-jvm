package com.faunadb.client.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.NullNode;
import com.faunadb.client.query.Expr;
import com.faunadb.client.query.Language;
import com.faunadb.client.types.time.HighPrecisionTime;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import org.joda.time.Instant;
import org.joda.time.LocalDate;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.faunadb.client.util.Objects.requireNonNull;
import static com.google.common.base.Joiner.on;
import static com.google.common.primitives.Bytes.asList;
import static java.lang.String.format;

/**
 * Represents any scalar or non-scalar value in the FaunaDB query language. FaunaDB value types consist of
 * all of the JSON value types, as well as the FaunaDB-specific types, {@link RefV} and {@link SetRefV}.
 * <p>
 * Scalar values are {@link LongV}, {@link StringV}, {@link DoubleV}, {@link BooleanV}, {@link NullV},
 * {@link RefV}, and {@link SetRefV}.
 * <p>
 * Non-scalar values are {@link ObjectV} and {@link ArrayV}.
 * <p>
 * This interface itself does not have any directly accessible data. It must first be coerced into a type before
 * its data can be accessed.
 * <p>
 * <b>Example</b>: Consider the {@link Value} node modeling the root of the tree:
 * <pre>{@code
 * {
 *   "ref": { "@ref": "some/ref" },
 *   "data": { "someKey": "string1", "someKey2": 123 }
 * }}</pre>
 * <p>
 * The result tree can be accessed using:
 * <pre>{@code
 *   Field<RefV> ref = Field.at("ref").to(Codec.REF);
 *   Field<String> someKey = Field.at("data", "someKey").to(Codec.STRING);
 *   Field<String> nonExistingKey = Field.at("non-existing-key").to(Codec.LONG);
 *
 *   node.get(ref); // new RefV("some/ref")
 *   node.get(someKey); // "string1"
 *   node.getOptional(nonExistingKey) // Optional.absent()
 * }</pre>
 * <p>
 * The interface also has helpers to transverse values without {@link Field} references:
 * <pre>{@code
 *   node.at("ref").to(Codec.REF).get(); // new RefV("some/ref")
 *   node.at("data", "someKey").to(Codec.STRING).get() // "string1"
 *   node.at("non-existing-key").to(Codec.LONG).getOptional() // Optional.absent()
 * }</pre>
 *
 * @see <a href="https://fauna.com/documentation/queries#values">FaunaDB Value Types</a>
 * @see Field
 * @see Codec
 */
@JsonDeserialize(using = Deserializer.ValueDeserializer.class)
public abstract class Value extends Expr {

  private Value() {
  }

  /**
   * Attempts to coerce this value using the {@link Codec} passed
   *
   * @param codec codec function to attempt coercion
   * @return the {@link Result} of the coercion
   * @see Codec
   */
  public final <T> Result<T> to(Codec<T> codec) {
    return codec.decode(this);
  }

  /**
   * Attempts to coerce this value using the {@link Decoder} class into the specified type
   *
   * @param clazz a class type to convert
   * @return the {@link Result} of the coercion
   * @see Decoder
   */
  public final <T> Result<T> to(Class<T> clazz) {
    return Decoder.decode(this, Types.of(clazz));
  }

  /**
   * Attempts to convert the specified object into the corresponding {@link Value} type
   *
   * @param obj the object instance to convert
   * @return the {@link Result} of the conversion
   */
  public static <T> Result<Value> from(T obj) {
    return Encoder.encode(obj);
  }

  /**
   * Attempts to coerce this value to a {@link Map}.
   * This method is only util if this object is an instance of {@link ObjectV}.
   *
   * @param valueType the type of the values.
   * @return a {@link Result} with the resulting map containing the keys/values.
   * @see Decoder
   * @see Types
   */
  public final <K, V> Result<Map<K, V>> asMapOf(Class<V> valueType) {
    return Decoder.decode(this, Types.hashMapOf(valueType));
  }

  /**
   * Attempts to coerce this value to a {@link Collection}.
   * This method is only util if this object is an instance of {@link ArrayV}.
   *
   * @param elementType the type of the elements in the collection.
   * @return a {@link Result} with the resulting collection.
   * @see Decoder
   * @see Types
   */
  public final <T> Result<Collection<T>> asCollectionOf(Class<T> elementType) {
    return Decoder.decode(this, Types.arrayListOf(elementType));
  }

  /**
   * Extract a {@link Field} from this node
   *
   * @param field field to extract
   * @return the resulting value of extracting the {@link Field} from this node
   * @throws IllegalStateException if {@link Field} does not exists on this node
   * @see Field
   */
  public final <T> T get(Field<T> field) {
    return field.get(this).get();
  }

  /**
   * Attempts to extact a {@link Field} from this node
   *
   * @param field field to extract
   * @return An {@link Optional} with the resulting value if the field's extraction was successful
   * @see Field
   */
  public final <T> Optional<T> getOptional(Field<T> field) {
    return field.get(this).getOptional();
  }

  /**
   * Loop through this node collecting the {@link Field} passed, assuming the node is an instance of {@link ArrayV}
   * <p>
   * <b>Example</b>: Consider the {@link Value} node modeling the root of the tree:
   * <pre>{@code
   * {
   *   "data": {
   *     "arrayOfStrings": ["Jhon", "Bill"],
   *     "arrayOfObjects": [ {"name": "Jhon"}, {"name": "Bill"} ]
   *    }
   * }}</pre>
   * <p>
   * The result tree can be accessed using:
   * <pre>{@code
   *   node.get("arrayOfStrings").collect(Field.to(Codec.STRING)); // ["Jhon", "Bill"]
   *
   *   Field<String> name = Field.at("name").to(Codec.STRING);
   *   node.get("arrayOfObjects").collect(name); // ["Jhon", "Bill"]
   * }</pre>
   *
   * @param field field to extract from each array value
   * @return a {@link ImmutableList} with the collected {@link Field}s
   * @see Field
   */
  public final <T> ImmutableList<T> collect(Field<T> field) {
    return Field.root().collect(field).get(this).get();
  }

  /**
   * Navigate through object's keys, assuming value is an instance of {@link ObjectV}.
   *
   * @param keys path to navigate to
   * @return {@link Value} under the path or {@link NullV}
   */
  public final Value at(String... keys) {
    return Field.at(keys).get(this).getOrElse(NullV.NULL);
  }

  /**
   * Navigate through array's indexes, assuming value is an instance of {@link ArrayV}
   *
   * @param indexes path to navigate to
   * @return {@link Value} under the path or {@link NullV}
   */
  public final Value at(int... indexes) {
    return Field.at(indexes).get(this).getOrElse(NullV.NULL);
  }

  /**
   * Represents a scalar value at the FaunaDB query language.
   * See {@link Value}
   */
  @JsonDeserialize(using = JsonDeserializer.None.class) // Disables generic value deserializer for scalar values
  static abstract class ScalarValue<T> extends Value {

    @JsonIgnore
    final T value;

    ScalarValue(T value) {
      this.value = requireNonNull(value);
    }

    @Override
    public boolean equals(Object other) {
      return other != null && other instanceof ScalarValue &&
        this.value.equals(((ScalarValue) other).value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public String toString() {
      return format("%s(%s)", getClass().getSimpleName(), value);
    }

  }

  /**
   * Represents an Object value in the FaunaDB query language. Objects are polymorphic dictionaries.
   *
   * @see Language#Obj
   */
  @JsonDeserialize(using = Deserializer.ObjectDeserializer.class)
  public static final class ObjectV extends Value {

    final ImmutableMap<String, Value> values;

    public ObjectV(Map<String, ? extends Value> values) {
      requireNonNull(values);
      this.values = ImmutableMap.copyOf(values);
    }

    @Override
    @JsonValue
    protected ImmutableMap<String, ImmutableMap<String, Value>> toJson() {
      return ImmutableMap.of("object", values);
    }

    @Override
    public boolean equals(Object other) {
      return other != null && other instanceof ObjectV &&
        this.values.equals(((ObjectV) other).values);
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }

    @Override
    public String toString() {
      return format("ObjectV(%s)", values);
    }
  }

  /**
   * Represents an array value in the FaunaDB query language. Arrays are polymorphic ordered lists of other values.
   *
   * @see Language#Arr
   */
  @JsonDeserialize(using = Deserializer.ArrayDeserializer.class)
  public static final class ArrayV extends Value {

    final ImmutableList<Value> values;

    public ArrayV(List<? extends Value> values) {
      requireNonNull(values);
      this.values = ImmutableList.copyOf(values);
    }

    @Override
    @JsonValue
    protected ImmutableList<Value> toJson() {
      return values;
    }

    @Override
    public boolean equals(Object other) {
      return other != null
        && other instanceof ArrayV &&
        this.values.equals(((ArrayV) other).values);
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }

    @Override
    public String toString() {
      return format("Arr(%s)", values);
    }
  }

  /**
   * Represents a Boolean value in the FaunaDB query language.
   *
   * @see Language#Value(boolean)
   */
  public static final class BooleanV extends ScalarValue<Boolean> {

    public final static BooleanV TRUE = new BooleanV(true);
    public final static BooleanV FALSE = new BooleanV(false);

    public static BooleanV valueOf(boolean value) {
      return value ? TRUE : FALSE;
    }

    private BooleanV(Boolean value) {
      super(value);
    }

    @Override
    @JsonValue
    protected Boolean toJson() {
      return value;
    }

  }

  /**
   * Represents a Double value in the FaunaDB query language.
   *
   * @see Language#Value(double)
   */
  public static final class DoubleV extends ScalarValue<Double> {
    public DoubleV(double value) {
      super(value);
    }

    @Override
    @JsonValue
    protected Double toJson() {
      return value;
    }
  }

  /**
   * Represents a Long value in the FaunaDB query language.
   *
   * @see Language#Value(long)
   */
  public static final class LongV extends ScalarValue<Long> {
    public LongV(long value) {
      super(value);
    }

    @Override
    @JsonValue
    protected Long toJson() {
      return value;
    }
  }

  /**
   * Represents a String value in the FaunaDB query language.
   *
   * @see Language#Value(String)
   */
  public static final class StringV extends ScalarValue<String> {
    public StringV(String value) {
      super(value);
    }

    @Override
    @JsonValue
    protected String toJson() {
      return value;
    }
  }

  /**
   * Represents a null value in the FaunaDB query language.
   *
   * @see Language#Null()
   */
  public static final class NullV extends Value {

    public static final NullV NULL = new NullV();

    private NullV() {
    }

    @Override
    @JsonValue
    protected NullNode toJson() {
      return NullNode.getInstance();
    }

    @Override
    public boolean equals(Object other) {
      return other != null
        && other instanceof NullV;
    }

    @Override
    public int hashCode() {
      return -1;
    }

    @Override
    public String toString() {
      return "NullV";
    }

  }

  /**
   * Represents a Timestamp value in the FaunaDB query language.
   *
   * @see Language#Value(Instant)
   */
  public static final class TimeV extends ScalarValue<HighPrecisionTime> {

    public TimeV(HighPrecisionTime value) {
      super(value);
    }

    @JsonCreator
    private TimeV(@JsonProperty("@ts") String value) {
      this(HighPrecisionTime.parse(value));
    }

    Instant truncated() {
      return value.toInstant();
    }

    @Override
    @JsonProperty("@ts")
    protected String toJson() {
      return value.toString();
    }

  }

  /**
   * Represents a Date value in the FaunaDB query language.
   *
   * @see Language#Value(LocalDate)
   */
  public static final class DateV extends ScalarValue<LocalDate> {

    public DateV(LocalDate value) {
      super(value);
    }

    @JsonCreator
    private DateV(@JsonProperty("@date") String value) {
      super(LocalDate.parse(value));
    }

    @Override
    @JsonProperty("@date")
    protected String toJson() {
      return value.toString();
    }
  }

  /**
   * A FaunaDB set literal.
   *
   * @see <a href="https://fauna.com/documentation/queries#values-special_types">FaunaDB Special Types</a>
   */
  public static final class SetRefV extends ScalarValue<ImmutableMap<String, Value>> {

    public SetRefV(@JsonProperty("@set") ImmutableMap<String, Value> parameters) {
      super(parameters);
    }

    /**
     * Extact SetRefV structure
     *
     * @return SetRefV structure
     */
    public ImmutableMap<String, Value> parameters() {
      return value;
    }

    @Override
    @JsonProperty("@set")
    protected ImmutableMap<String, Value> toJson() {
      return value;
    }
  }

  /**
   * A FaunaDB ref type.
   *
   * @see <a href="https://fauna.com/documentation/queries#values-special_types">FaunaDB Special Types</a>
   */
  public static final class RefV extends Value.ScalarValue<String> {

    @JsonCreator
    public RefV(@JsonProperty("@ref") String value) {
      super(value);
    }

    @Override
    @JsonProperty("@ref")
    protected String toJson() {
      return value;
    }

    /**
     * Extracts its string value.
     *
     * @return a string with the ref value
     */
    public String strValue() {
      return value;
    }
  }

  /**
   * Represents a blob value in the FaunaDB query language.
   *
   * @see <a href="https://fauna.com/documentation/queries#values-special_types">FaunaDB Special Types</a>
   */
  public static final class BytesV extends ScalarValue<byte[]> {

    public BytesV(byte[] bytes) {
      super(bytes);
    }

    @JsonCreator
    public BytesV(@JsonProperty("@bytes") String urlSafeBase64) {
      super(BaseEncoding.base64Url().decode(urlSafeBase64));
    }

    @Override
    @JsonProperty("@bytes")
    protected Object toJson() {
      return BaseEncoding.base64Url().encode(value);
    }

    @Override
    public boolean equals(Object other) {
      return other != null && other instanceof BytesV &&
        Arrays.equals(this.value, ((BytesV) other).value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public String toString() {
      String str = FluentIterable.from(asList(value)).transform(new Function<Byte, String>() {
        @Override
        public String apply(Byte input) {
          return format("0x%02x", input);
        }
      }).join(on(", "));

      return format("BytesV(%s)", str);
    }
  }

  /**
   * Represents a query value in the FaunaDB query language.
   *
   * @see <a href="https://fauna.com/documentation/queries#values-special_types">FaunaDB Special Types</a>
   */
  @JsonDeserialize(using = JsonDeserializer.None.class) // Disables generic value deserializer for query values
  public static final class QueryV extends Value {

    private Map<String, Object> lambda;

    private QueryV(@JsonProperty("@query") Map<String, Object> lambda) {
      this.lambda = lambda;
    }

    @Override
    @JsonProperty("@query")
    protected Map<String, Object> toJson() {
      return lambda;
    }

    @Override
    public boolean equals(Object obj) {
      return obj != null && obj instanceof QueryV && lambda.equals(((QueryV)obj).lambda);
    }

    @Override
    public int hashCode() {
      return lambda.hashCode();
    }
  }
}
