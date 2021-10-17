import * as jspb from 'google-protobuf'



export class InputProto extends jspb.Message {
  getUtf8(): string;
  setUtf8(value: string): InputProto;

  serializeBinary(): Uint8Array;
  toObject(includeInstance?: boolean): InputProto.AsObject;
  static toObject(includeInstance: boolean, msg: InputProto): InputProto.AsObject;
  static serializeBinaryToWriter(message: InputProto, writer: jspb.BinaryWriter): void;
  static deserializeBinary(bytes: Uint8Array): InputProto;
  static deserializeBinaryFromReader(message: InputProto, reader: jspb.BinaryReader): InputProto;
}

export namespace InputProto {
  export type AsObject = {
    utf8: string,
  }
}

export class OutputProto extends jspb.Message {
  getC(): number;
  setC(value: number): OutputProto;

  getM(): string;
  setM(value: string): OutputProto;

  getUtf8(): string;
  setUtf8(value: string): OutputProto;

  getBs(): Uint8Array | string;
  getBs_asU8(): Uint8Array;
  getBs_asB64(): string;
  setBs(value: Uint8Array | string): OutputProto;

  getDataCase(): OutputProto.DataCase;

  serializeBinary(): Uint8Array;
  toObject(includeInstance?: boolean): OutputProto.AsObject;
  static toObject(includeInstance: boolean, msg: OutputProto): OutputProto.AsObject;
  static serializeBinaryToWriter(message: OutputProto, writer: jspb.BinaryWriter): void;
  static deserializeBinary(bytes: Uint8Array): OutputProto;
  static deserializeBinaryFromReader(message: OutputProto, reader: jspb.BinaryReader): OutputProto;
}

export namespace OutputProto {
  export type AsObject = {
    c: number,
    m: string,
    utf8: string,
    bs: Uint8Array | string,
  }

  export enum DataCase { 
    DATA_NOT_SET = 0,
    UTF8 = 3,
    BS = 4,
  }
}

