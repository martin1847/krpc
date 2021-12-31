import {RpcResult,RpcService,Headers} from '@btyx/rpc';

import {
  IsInt,
  Length,
  IsEmail,
  IsFQDN,
  IsDate,
  Min,
  Max,
  IsOptional,
  IsDefined,
  IsNotEmpty,
  MinLength,
  MaxLength,
  IsPositive,
  IsNegative,
} from 'class-validator';

export const APP = '${app}';


<#list dtos as dto>
export ${dto.input?then('class','interface')}  ${dto.typeName} {

    <#assign hasRequired = false>
    <#list dto.fields?filter(f->! f.hidden) as f>
    ${f.doc}
    ${f.name}${(dto.input && !f.required)?then('?','')}: ${f.type};
    <#if f.required>
    <#assign hasRequired = true >
    </#if>
    </#list>

    <#if hasRequired>

	constructor(<#list dto.fields?filter(f->f.required) as f>${f.name}: ${f.type}${f?has_next?then(',','')}</#list>) {
		<#list dto.fields?filter(f->f.required) as f>
		this.${f.name} = ${f.name};
		</#list>
	}
        </#if>
}

</#list>



export class Precheck{

	@MinLength(1)
	@Length(0, 50)
	username: string;

	// 验证码，可以为空如果未启用
	@Length(1, 100)
	captcha?: string;
	captchaType?: number;
	captchaSign?: string;

	constructor(username: string) {
		this.username = username;
	}
}
