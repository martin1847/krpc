import * as jspb from 'google-protobuf'



export class InputMessage extends jspb.Message {
  getSe(): SerEnum;
  setSe(value: SerEnum): InputMessage;

  getB(): string;
  setB(value: string): InputMessage;

  serializeBinary(): Uint8Array;
  toObject(includeInstance?: boolean): InputMessage.AsObject;
  static toObject(includeInstance: boolean, msg: InputMessage): InputMessage.AsObject;
  static serializeBinaryToWriter(message: InputMessage, writer: jspb.BinaryWriter): void;
  static deserializeBinary(bytes: Uint8Array): InputMessage;
  static deserializeBinaryFromReader(message: InputMessage, reader: jspb.BinaryReader): InputMessage;
}

export namespace InputMessage {
  export type AsObject = {
    se: SerEnum,
    b: string,
  }
}

export class OutputMessage extends jspb.Message {
  getC(): number;
  setC(value: number): OutputMessage;

  getM(): string;
  setM(value: string): OutputMessage;

  getSe(): SerEnum;
  setSe(value: SerEnum): OutputMessage;

  getS(): string;
  setS(value: string): OutputMessage;

  getB(): Uint8Array | string;
  getB_asU8(): Uint8Array;
  getB_asB64(): string;
  setB(value: Uint8Array | string): OutputMessage;

  getPCase(): OutputMessage.PCase;

  serializeBinary(): Uint8Array;
  toObject(includeInstance?: boolean): OutputMessage.AsObject;
  static toObject(includeInstance: boolean, msg: OutputMessage): OutputMessage.AsObject;
  static serializeBinaryToWriter(message: OutputMessage, writer: jspb.BinaryWriter): void;
  static deserializeBinary(bytes: Uint8Array): OutputMessage;
  static deserializeBinaryFromReader(message: OutputMessage, reader: jspb.BinaryReader): OutputMessage;
}

export namespace OutputMessage {
  export type AsObject = {
    c: number,
    m: string,
    se: SerEnum,
    s: string,
    b: Uint8Array | string,
  }

  export enum PCase { 
    P_NOT_SET = 0,
    S = 4,
    B = 5,
  }
}

export enum SerEnum { 
  NONE = 0,
  JSON = 1,
  MSG_PACK = 2,
  HESSIAN = 3,
  KRYO = 4,
  PSR = 5,
}
