/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package test.gen;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

/**
 *
 * struct Class_File_Format {
 *    u4 magic_number;  // unsigned 32-bit integer in big-endian byte order
 *
 *    u2 minor_version; // unsigned 16-bit integer in big-endian byte order
 *    u2 major_version;
 *
 *    u2 constant_pool_count;
 *
 *    cp_info constant_pool[constant_pool_count - 1];
 *
 *    u2 access_flags;
 *
 *    u2 this_class;
 *    u2 super_class;
 *
 *    u2 interfaces_count;
 *
 *    u2 interfaces[interfaces_count];
 *
 *    u2 fields_count;
 *    field_info fields[fields_count];
 *
 *    u2 methods_count;
 *    method_info methods[methods_count];
 *
 *    u2 attributes_count;
 *    attribute_info attributes[attributes_count];
 * }
 *
 * @author Martin.C
 * @version 2022/12/12 13:59
 */
public class TestParseClassFile implements Serializable {

    String name;

    static  final String NAME  = "TestParse中文";

    static final String FILE_NAME = TestParseClassFile.class.getSimpleName() + ".class";


    static class Parser{
        final InputStream in;
        final byte[] u2buf = new byte[2];

        public Parser(InputStream in) {
            this.in = in;
        }

        int readUShort() throws IOException {
            in.read(u2buf);
            // & int 0xff -> unsigned
            return ((u2buf[0] & 0xff) << 8) | Byte.toUnsignedInt(u2buf[1]);
        }

        String readMagic() throws IOException {
            var bytes = new byte[4];
            in.read(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) {
                    hex = '0' + hex;
                }
                sb.append(hex.toUpperCase());
            }
            return sb.toString();
        }


        int readConstantPool() throws IOException {
            var poolCnt = readUShort();
            for (int i = 0; i < poolCnt - 1; i++) {
                    int tag = (byte)in.read();
                    int u2 = readUShort();
                    switch (tag){
                        case CpInfo.UTF8:
                            var buf = new byte[u2];
                            in.read(buf);
                            System.out.println("UTF8:"+u2+": "+new String(buf));
                            break;
                        case CpInfo.CLASS:
                        case CpInfo.STRING:
                            System.out.print(tag == CpInfo.CLASS ? "CLASS:":"STRING:");
                            System.out.println(" index->"+u2);
                            break;
                        default:
                            System.out.println("unknowtag:"+tag+",index:"+u2);
                    }
            }
            return poolCnt;
        }
    }

    interface   CpInfo {
        //one length and the value : new String(reader.readOrigin(length))
        int UTF8 = 1;
        //one index
        int CLASS = 7;
        //one index
        int STRING = 8;

        int FIELD_REF = 9;
        int METHOD_REF = 10;
        int NAME_AND_TYPE = 12;
    }


    public static void main(String[] args) throws IOException {
        var in =
                TestParseClassFile.class.getResourceAsStream(FILE_NAME);
        var parse = new Parser(in);
        //CAFEBABE
        System.out.println(parse.readMagic());
        System.out.println("minor_version :" + parse.readUShort());
        //Java SE 8 = 52 (0x34 hex),
        //Java SE 11 = 55 (0x37 hex),
        //Java SE 17 = 61 (0x3D hex),
        System.out.println("major_version :" + parse.readUShort());
        System.out.println("constant_pool_count :" + parse.readConstantPool());



        in.close();
    }

    public static void log(String format, Object... params) {
        System.out.printf(format, params);
        System.out.println();
    }

    //static void dumpCp(){
    //        try {
    //            int constant_pool_count = reader.readUnsignedShort();
    //            log("constant_pool_count: %d", constant_pool_count);
    //
    //            for (int i = 0; i < constant_pool_count - 1; i++) {
    //
    //                int tag = reader.readUnsignedByte();
    //                switch (tag) {
    //                    case ConstantTag.METHOD_REF:
    //                        ConstantMethodref methodRef = new ConstantMethodref();
    //                        methodRef.read(reader);
    //                        log("%s", methodRef.toString());
    //                        break;
    //
    //                    case ConstantTag.FIELD_REF:
    //                        ConstantFieldRef fieldRef = new ConstantFieldRef();
    //                        fieldRef.read(reader);
    //                        log("%s", fieldRef.toString());
    //                        break;
    //
    //                    case ConstantTag.STRING:
    //                        ConstantString string = new ConstantString();
    //                        string.read(reader);
    //                        log("%s", string.toString());
    //                        break;
    //
    //                    case ConstantTag.CLASS:
    //                        ConstantClass clazz = new ConstantClass();
    //                        clazz.read(reader);
    //                        log("%s", clazz.toString());
    //                        break;
    //
    //                    case ConstantTag.UTF8:
    //                        ConstantUtf8 utf8 = new ConstantUtf8();
    //                        utf8.read(reader);
    //                        log("%s", utf8.toString());
    //                        break;
    //
    //                    case ConstantTag.NAME_AND_TYPE:
    //                        ConstantNameAndType nameAndType = new ConstantNameAndType();
    //                        nameAndType.read(reader);
    //                        log("%s", nameAndType.toString());
    //                        break;
    //
    //                }
    //
    //            }
    //        } catch (IOException e) {
    //            log("Parser constant pool error:%s", e.getMessage());
    //        }
    //}

    //big-endian
    static int bytes2Ushort(byte[] bytes) {
        if (null == bytes || bytes.length == 0) return 0;
        // & int 0xff -> unsigned
        return ((bytes[0] & 0xff) << 8) | Byte.toUnsignedInt(bytes[1]);
    }

    static String byte2HexStr(byte[] bytes) {
        if (null == bytes || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xFF);
            if (hex.length() == 1) {
                hex = '0' + hex;
            }
            sb.append(hex.toUpperCase());
        }
        return sb.toString();
    }

}