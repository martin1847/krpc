// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: internal.proto

package tech.krpc.internal;

public interface OutputProtoOrBuilder {
//  extends
//  // @@protoc_insertion_point(interface_extends:tech.krpc.internal.OutputProto)
//  com.google.protobuf.MessageOrBuilder
//{

  /**
   * <pre>
   *google.rpc.Code  see https://github.com/googleapis/googleapis/blob/master/google/rpc/code.proto
   * </pre>
   *
   * <code>int32 c = 1;</code>
   * @return The c.
   */
  int getC();

  /**
   * <pre>
   *detail  message if there is a error ,otherwise  null 
   * </pre>
   *
   * <code>string m = 2;</code>
   * @return The m.
   */
  String getM();
  /**
  // * <pre>
  // *detail  message if there is a error ,otherwise  null
  // * </pre>
  // *
  // * <code>string m = 2;</code>
  // * @return The bytes for m.
  // */
  //com.google.protobuf.ByteString
  //    getMBytes();

  /**
   * <pre>
   *&#47;/for json , txt like serial
   * </pre>
   *
   * <code>string utf8 = 3;</code>
   * @return Whether the utf8 field is set.
   */
  boolean hasUtf8();
  /**
   * <pre>
   *&#47;/for json , txt like serial
   * </pre>
   *
   * <code>string utf8 = 3;</code>
   * @return The utf8.
   */
  String getUtf8();
  /**
   * <pre>
   *&#47;/for json , txt like serial
   * </pre>
  // *
  // * <code>string utf8 = 3;</code>
  // * @return The bytes for utf8.
  // */
  //com.google.protobuf.ByteString
  //    getUtf8Bytes();

  /**
   * <pre>
   * ByteString, others Serialization transfer by protobuf
   * </pre>
   *
   * <code>bytes bs = 4;</code>
   * @return Whether the bs field is set.
   */
  boolean hasBs();
  /**
   * <pre>
   * ByteString, others Serialization transfer by protobuf
   * </pre>
   *
   * <code>bytes bs = 4;</code>
   * @return The bs.
   */
  byte[] getBs();

  public OutputProto.DataCase getDataCase();


  interface OutBuilder{
    OutBuilder setC(int value);
    OutBuilder  setM(String value);
    OutBuilder setUtf8( String value);
    OutBuilder setBs(byte[] value);
  }
}
