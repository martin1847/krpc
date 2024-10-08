// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: internal.proto

package tech.krpc.internal;

/**
 * Protobuf enum {@code tech.krpc.internal.SerialEnum}
 */
public enum SerialEnum{
  /**
   * <code>JSON = 0;</code>
   */
  JSON(0),
  /**
   * <code>HESSIAN = 1;</code>
   */
  HESSIAN(1),
  /**
   * <code>MSG_PACK = 2;</code>
   */
  MSG_PACK(2),
  /**
   * <code>KRYO = 3;</code>
   */
  KRYO(3),
  /**
   * <code>PSR = 4;</code>
   */
  PSR(4),
  UNRECOGNIZED(99),
  ;

  /**
   * <code>JSON = 0;</code>
   */
  public static final int JSON_VALUE = 0;
  /**
   * <code>HESSIAN = 1;</code>
   */
  public static final int HESSIAN_VALUE = 1;
  /**
   * <code>MSG_PACK = 2;</code>
   */
  public static final int MSG_PACK_VALUE = 2;
  /**
   * <code>KRYO = 3;</code>
   */
  public static final int KRYO_VALUE = 3;
  /**
   * <code>PSR = 4;</code>
   */
  public static final int PSR_VALUE = 4;


  public final int getNumber() {
    if (this == UNRECOGNIZED) {
      throw new IllegalArgumentException(
          "Can't get the number of an unknown enum value.");
    }
    return value;
  }

  /**
  // * @param value The numeric wire value of the corresponding enum entry.
  // * @return The enum associated with the given numeric wire value.
  // * @deprecated Use {@link #forNumber(int)} instead.
  // */
  //@Deprecated
  //public static SerialEnum valueOf(int value) {
  //  return forNumber(value);
  //}

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   */
  public static SerialEnum forNumber(int value) {
    switch (value) {
      case 0: return JSON;
      case 1: return HESSIAN;
      case 2: return MSG_PACK;
      case 3: return KRYO;
      case 4: return PSR;
      default: return null;
    }
  }

  //public static com.google.protobuf.Internal.EnumLiteMap<SerialEnum>
  //    internalGetValueMap() {
  //  return internalValueMap;
  //}
  //private static final com.google.protobuf.Internal.EnumLiteMap<
  //    SerialEnum> internalValueMap =
  //      new com.google.protobuf.Internal.EnumLiteMap<SerialEnum>() {
  //        public SerialEnum findValueByNumber(int number) {
  //          return SerialEnum.forNumber(number);
  //        }
  //      };
  //
  //public final com.google.protobuf.Descriptors.EnumValueDescriptor
  //    getValueDescriptor() {
  //  if (this == UNRECOGNIZED) {
  //    throw new IllegalStateException(
  //        "Can't get the descriptor of an unrecognized enum value.");
  //  }
  //  return getDescriptor().getValues().get(ordinal());
  //}
  //public final com.google.protobuf.Descriptors.EnumDescriptor
  //    getDescriptorForType() {
  //  return getDescriptor();
  //}
  //public static final com.google.protobuf.Descriptors.EnumDescriptor
  //    getDescriptor() {
  //  return Internal.getDescriptor().getEnumTypes().get(0);
  //}

  //private static final SerialEnum[] VALUES = values();

  //public static SerialEnum valueOf(
  //    com.google.protobuf.Descriptors.EnumValueDescriptor desc) {
  //  if (desc.getType() != getDescriptor()) {
  //    throw new IllegalArgumentException(
  //      "EnumValueDescriptor is not for this type.");
  //  }
  //  if (desc.getIndex() == -1) {
  //    return UNRECOGNIZED;
  //  }
  //  return VALUES[desc.getIndex()];
  //}

  private final int value;

  private SerialEnum(int value) {
    this.value = value;
  }

  // @@protoc_insertion_point(enum_scope:tech.krpc.internal.SerialEnum)
}

