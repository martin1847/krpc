/**
* GENERATED BY BTYX RPC GEN - LESS MODIFY BY HAND
* ${dtoFile}  ${.now?iso_local}
*/

<#assign isTs = (lang == 'Typescript')>

import {RpcResult,RpcService,Headers} from ${isTs?then("'@btyx/rpc'","'../utils/rpc'")};

<#if isTs>
import {
  IsInt,
  Length,
  IsEmail,
  IsFQDN,
  IsDate,
  IsArray,
  Min,
  Max,
  IsOptional,
  IsDefined,
  IsNotEmpty,
  MinLength,
  MaxLength,
  ArrayMinSize,
  ArrayMaxSize,
  IsPositive,
  IsNegative,
} from 'class-validator';
</#if>

export const APP = '${app}';

<#list dtos as dto>
<#if dto.doc?has_content>/// ${dto.doc}</#if>
<#if dto.enum>
export const enum ${dto.typeName} {
	<#list dto.fields as f>
	${f.name} = "${f.name}"${f?has_next?then(',','')}
	</#list>
}
<#else>
export ${dto.input?then('class','interface')}  ${dto.typeName} {
 <#assign hasRequired = false>
 <#list dto.fields?filter(f->! f.hidden) as f>


    <#list (f.annotations![])?filter(a->a.name?has_content) as anno>
    <#if anno?is_last && dto.input && !f.required && isTs>
    ${anno.name}
    @IsOptional()
    <#else>
    ${ ( dto.input && isTs )?then('','//')}${anno.name}
    </#if>
    </#list>
    ${f.name}${(dto.input && !f.required)?then('?','')}: ${f.type};
    <#if dto.input && f.required>
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
</#if>

</#list>
