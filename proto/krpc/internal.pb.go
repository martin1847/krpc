// Code generated by protoc-gen-go. DO NOT EDIT.
// versions:
// 	protoc-gen-go v1.28.0
// 	protoc        v3.19.4
// source: internal.proto

package krpc

import (
	protoreflect "google.golang.org/protobuf/reflect/protoreflect"
	protoimpl "google.golang.org/protobuf/runtime/protoimpl"
	reflect "reflect"
	sync "sync"
)

const (
	// Verify that this generated code is sufficiently up-to-date.
	_ = protoimpl.EnforceVersion(20 - protoimpl.MinVersion)
	// Verify that runtime/protoimpl is sufficiently up-to-date.
	_ = protoimpl.EnforceVersion(protoimpl.MaxVersion - 20)
)

type InputProto struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	// int32 e = 1; // default from SerialEnum.JSON = 0 , not allow modify
	Utf8 string `protobuf:"bytes,2,opt,name=utf8,proto3" json:"utf8,omitempty"`
}

func (x *InputProto) Reset() {
	*x = InputProto{}
	if protoimpl.UnsafeEnabled {
		mi := &file_internal_proto_msgTypes[0]
		ms := protoimpl.X.MessageStateOf(protoimpl.Pointer(x))
		ms.StoreMessageInfo(mi)
	}
}

func (x *InputProto) String() string {
	return protoimpl.X.MessageStringOf(x)
}

func (*InputProto) ProtoMessage() {}

func (x *InputProto) ProtoReflect() protoreflect.Message {
	mi := &file_internal_proto_msgTypes[0]
	if protoimpl.UnsafeEnabled && x != nil {
		ms := protoimpl.X.MessageStateOf(protoimpl.Pointer(x))
		if ms.LoadMessageInfo() == nil {
			ms.StoreMessageInfo(mi)
		}
		return ms
	}
	return mi.MessageOf(x)
}

// Deprecated: Use InputProto.ProtoReflect.Descriptor instead.
func (*InputProto) Descriptor() ([]byte, []int) {
	return file_internal_proto_rawDescGZIP(), []int{0}
}

func (x *InputProto) GetUtf8() string {
	if x != nil {
		return x.Utf8
	}
	return ""
}

type OutputProto struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	//google.rpc.Code  see https://github.com/googleapis/googleapis/blob/master/google/rpc/code.proto
	C int32 `protobuf:"varint,1,opt,name=c,proto3" json:"c,omitempty"`
	//detail  message if there is a error ,otherwise  null
	// payload data , oneof  string or bytes
	//
	// Types that are assignable to Data:
	//	*OutputProto_M
	//	*OutputProto_Utf8
	//	*OutputProto_Bs
	Data isOutputProto_Data `protobuf_oneof:"data"`
}

func (x *OutputProto) Reset() {
	*x = OutputProto{}
	if protoimpl.UnsafeEnabled {
		mi := &file_internal_proto_msgTypes[1]
		ms := protoimpl.X.MessageStateOf(protoimpl.Pointer(x))
		ms.StoreMessageInfo(mi)
	}
}

func (x *OutputProto) String() string {
	return protoimpl.X.MessageStringOf(x)
}

func (*OutputProto) ProtoMessage() {}

func (x *OutputProto) ProtoReflect() protoreflect.Message {
	mi := &file_internal_proto_msgTypes[1]
	if protoimpl.UnsafeEnabled && x != nil {
		ms := protoimpl.X.MessageStateOf(protoimpl.Pointer(x))
		if ms.LoadMessageInfo() == nil {
			ms.StoreMessageInfo(mi)
		}
		return ms
	}
	return mi.MessageOf(x)
}

// Deprecated: Use OutputProto.ProtoReflect.Descriptor instead.
func (*OutputProto) Descriptor() ([]byte, []int) {
	return file_internal_proto_rawDescGZIP(), []int{1}
}

func (x *OutputProto) GetC() int32 {
	if x != nil {
		return x.C
	}
	return 0
}

func (m *OutputProto) GetData() isOutputProto_Data {
	if m != nil {
		return m.Data
	}
	return nil
}

func (x *OutputProto) GetM() string {
	if x, ok := x.GetData().(*OutputProto_M); ok {
		return x.M
	}
	return ""
}

func (x *OutputProto) GetUtf8() string {
	if x, ok := x.GetData().(*OutputProto_Utf8); ok {
		return x.Utf8
	}
	return ""
}

func (x *OutputProto) GetBs() []byte {
	if x, ok := x.GetData().(*OutputProto_Bs); ok {
		return x.Bs
	}
	return nil
}

type isOutputProto_Data interface {
	isOutputProto_Data()
}

type OutputProto_M struct {
	M string `protobuf:"bytes,2,opt,name=m,proto3,oneof"`
}

type OutputProto_Utf8 struct {
	Utf8 string `protobuf:"bytes,3,opt,name=utf8,proto3,oneof"` ////for json , txt like serial
}

type OutputProto_Bs struct {
	Bs []byte `protobuf:"bytes,4,opt,name=bs,proto3,oneof"` // ByteString, others Serialization transfer by protobuf
}

func (*OutputProto_M) isOutputProto_Data() {}

func (*OutputProto_Utf8) isOutputProto_Data() {}

func (*OutputProto_Bs) isOutputProto_Data() {}

var File_internal_proto protoreflect.FileDescriptor

var file_internal_proto_rawDesc = []byte{
	0x0a, 0x0e, 0x69, 0x6e, 0x74, 0x65, 0x72, 0x6e, 0x61, 0x6c, 0x2e, 0x70, 0x72, 0x6f, 0x74, 0x6f,
	0x22, 0x20, 0x0a, 0x0a, 0x49, 0x6e, 0x70, 0x75, 0x74, 0x50, 0x72, 0x6f, 0x74, 0x6f, 0x12, 0x12,
	0x0a, 0x04, 0x75, 0x74, 0x66, 0x38, 0x18, 0x02, 0x20, 0x01, 0x28, 0x09, 0x52, 0x04, 0x75, 0x74,
	0x66, 0x38, 0x22, 0x5b, 0x0a, 0x0b, 0x4f, 0x75, 0x74, 0x70, 0x75, 0x74, 0x50, 0x72, 0x6f, 0x74,
	0x6f, 0x12, 0x0c, 0x0a, 0x01, 0x63, 0x18, 0x01, 0x20, 0x01, 0x28, 0x05, 0x52, 0x01, 0x63, 0x12,
	0x0e, 0x0a, 0x01, 0x6d, 0x18, 0x02, 0x20, 0x01, 0x28, 0x09, 0x48, 0x00, 0x52, 0x01, 0x6d, 0x12,
	0x14, 0x0a, 0x04, 0x75, 0x74, 0x66, 0x38, 0x18, 0x03, 0x20, 0x01, 0x28, 0x09, 0x48, 0x00, 0x52,
	0x04, 0x75, 0x74, 0x66, 0x38, 0x12, 0x10, 0x0a, 0x02, 0x62, 0x73, 0x18, 0x04, 0x20, 0x01, 0x28,
	0x0c, 0x48, 0x00, 0x52, 0x02, 0x62, 0x73, 0x42, 0x06, 0x0a, 0x04, 0x64, 0x61, 0x74, 0x61, 0x32,
	0x29, 0x0a, 0x04, 0x47, 0x72, 0x70, 0x63, 0x12, 0x21, 0x0a, 0x04, 0x63, 0x61, 0x6c, 0x6c, 0x12,
	0x0b, 0x2e, 0x49, 0x6e, 0x70, 0x75, 0x74, 0x50, 0x72, 0x6f, 0x74, 0x6f, 0x1a, 0x0c, 0x2e, 0x4f,
	0x75, 0x74, 0x70, 0x75, 0x74, 0x50, 0x72, 0x6f, 0x74, 0x6f, 0x42, 0x19, 0x5a, 0x17, 0x67, 0x6f,
	0x6f, 0x67, 0x6c, 0x65, 0x2e, 0x67, 0x6f, 0x6c, 0x61, 0x6e, 0x67, 0x2e, 0x6f, 0x72, 0x67, 0x2f,
	0x62, 0x74, 0x72, 0x70, 0x63, 0x62, 0x06, 0x70, 0x72, 0x6f, 0x74, 0x6f, 0x33,
}

var (
	file_internal_proto_rawDescOnce sync.Once
	file_internal_proto_rawDescData = file_internal_proto_rawDesc
)

func file_internal_proto_rawDescGZIP() []byte {
	file_internal_proto_rawDescOnce.Do(func() {
		file_internal_proto_rawDescData = protoimpl.X.CompressGZIP(file_internal_proto_rawDescData)
	})
	return file_internal_proto_rawDescData
}

var file_internal_proto_msgTypes = make([]protoimpl.MessageInfo, 2)
var file_internal_proto_goTypes = []interface{}{
	(*InputProto)(nil),  // 0: InputProto
	(*OutputProto)(nil), // 1: OutputProto
}
var file_internal_proto_depIdxs = []int32{
	0, // 0: Grpc.call:input_type -> InputProto
	1, // 1: Grpc.call:output_type -> OutputProto
	1, // [1:2] is the sub-list for method output_type
	0, // [0:1] is the sub-list for method input_type
	0, // [0:0] is the sub-list for extension type_name
	0, // [0:0] is the sub-list for extension extendee
	0, // [0:0] is the sub-list for field type_name
}

func init() { file_internal_proto_init() }
func file_internal_proto_init() {
	if File_internal_proto != nil {
		return
	}
	if !protoimpl.UnsafeEnabled {
		file_internal_proto_msgTypes[0].Exporter = func(v interface{}, i int) interface{} {
			switch v := v.(*InputProto); i {
			case 0:
				return &v.state
			case 1:
				return &v.sizeCache
			case 2:
				return &v.unknownFields
			default:
				return nil
			}
		}
		file_internal_proto_msgTypes[1].Exporter = func(v interface{}, i int) interface{} {
			switch v := v.(*OutputProto); i {
			case 0:
				return &v.state
			case 1:
				return &v.sizeCache
			case 2:
				return &v.unknownFields
			default:
				return nil
			}
		}
	}
	file_internal_proto_msgTypes[1].OneofWrappers = []interface{}{
		(*OutputProto_M)(nil),
		(*OutputProto_Utf8)(nil),
		(*OutputProto_Bs)(nil),
	}
	type x struct{}
	out := protoimpl.TypeBuilder{
		File: protoimpl.DescBuilder{
			GoPackagePath: reflect.TypeOf(x{}).PkgPath(),
			RawDescriptor: file_internal_proto_rawDesc,
			NumEnums:      0,
			NumMessages:   2,
			NumExtensions: 0,
			NumServices:   1,
		},
		GoTypes:           file_internal_proto_goTypes,
		DependencyIndexes: file_internal_proto_depIdxs,
		MessageInfos:      file_internal_proto_msgTypes,
	}.Build()
	File_internal_proto = out.File
	file_internal_proto_rawDesc = nil
	file_internal_proto_goTypes = nil
	file_internal_proto_depIdxs = nil
}