//package tech.krpc;
//
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Map;
//import java.util.Set;
//import java.util.stream.Collectors;
//
//import tech.krpc.common.meta.Anno;
//import tech.krpc.common.meta.Api;
//import tech.krpc.common.meta.ApiMeta;
//import tech.krpc.common.meta.Dto;
//import tech.krpc.common.meta.Method;
//import tech.krpc.common.meta.Property;
//
//public class TsClientBulider {
//
//    private static String importLine = "import {RpcResult,RpcService,Headers} from '@zlkj/rpc';\n\n";
//    private static String importValidator = "import {\n" +
//            "  IsInt,\n" +
//            "  Length,\n" +
//            "  IsEmail,\n" +
//            "  IsFQDN,\n" +
//            "  IsDate,\n" +
//            "  Min,\n" +
//            "  Max,\n" +
//            "  IsOptional,\n" +
//            "  IsDefined,\n" +
//            "  IsNotEmpty,\n" +
//            "  MinLength,\n" +
//            "  MaxLength,\n" +
//            "  IsPositive,\n" +
//            "  IsNegative,\n" +
//            "} from 'class-validator';\n\n";
//
//    public static Map<String, String> buildTsFile(ApiMeta meta){
//        String appName = meta.getApp();
//        Map<String, String> fileMap = new HashMap<>();
//
//        final Map<String, List<Property>> dtoMap = new HashMap<>();
//        fileMap.put(appName, printDto(appName, meta, dtoMap));
//
//        meta.getApis().forEach(a->printService(fileMap, appName, dtoMap, a));
//
//        return fileMap;
//    }
//
//    private static String printDto(String appName, ApiMeta meta, Map<String, List<Property>> dtoMap){
//        final StringBuilder dtoSb = new StringBuilder();
//        dtoSb.append(importLine);
//        dtoSb.append(importValidator);
//        dtoSb.append("export const APP = '" + appName + "';\n\n");
//
//        List<Method> dtoMethodList = meta.getApis().stream()
//                .filter(api->!api.getName().startsWith("-"))
//                .flatMap(a->a.getMethods().stream()).filter(m->
//                        m.getArg()!=null&&m.getArg().getFields()!=null || m.getRes()!=null&&m.getRes().getFields()!=null)
//                .collect(Collectors.toList());
//        dtoMethodList.stream().filter(m->m.getArg()!=null&&m.getArg().getFields()!=null)
//                .map(m->m.getArg()).forEach(a->parseDto(dtoMap, a));
//        dtoMethodList.stream().filter(m->m.getRes()!=null&&m.getRes().getFields()!=null)
//                .map(m->m.getRes()).forEach(a->parseDto(dtoMap, a));
//        dtoMap.entrySet().stream().forEach(e->parseProperties(dtoSb, e.getKey(), e.getValue()));
//        return dtoSb.toString();
//    }
//
//    private static void parseDto(Map<String, List<Property>> dtoMap, Dto dto){
//        String name = dto.getName();
//        if(name.contains("<")){
//            name = name.substring(0, name.indexOf("<"))+"<T>";
//        }
//        if(!dtoMap.containsKey(name)){
//            dtoMap.put(name, dto.getFields());
//        }
//    }
//
//    private static void parseProperties(StringBuilder sb, String name, List<Property> ps){
//        sb.append("export class " + name + "{\n\n");
//        Map<String, String> conParams = new HashMap<>();
//        for(Property p : ps){
//            boolean isMust = judgePropertyAnno(p.getAnnotations());
//            String anno = convertPropertyAnno(p.getAnnotations());
//            String type = convertPropertyType(p.getType());
//            if(isMust){
//                conParams.put(p.getName(), type);
//            }
//
//            sb.append(anno);
//            sb.append("\t" + p.getName() + (isMust? "":"?") + ": " + type + ";\n\n");
//        }
//
//        if(conParams.size() > 0){
//            sb.append("\tconstructor(");
//            for(Map.Entry<String, String> e : conParams.entrySet()){
//                sb.append(e.getKey());
//                sb.append(": ");
//                sb.append(e.getValue());
//                sb.append(", ");
//            }
//            sb.delete(sb.length()-2, sb.length());
//            sb.append(") {\n");
//            for(String s : conParams.keySet()){
//                sb.append("\t\tthis." + s + " = " + s + ";\n");
//            }
//            sb.append("\t}\n");
//        }
//        sb.append("}\n\n");
//    }
//
//    private static String convertPropertyAnno(List<Anno> annos){
//       if(annos!=null && annos.size()>0){
//           StringBuilder sb2 = new StringBuilder();
//           for(Anno anno : annos){
//               if("Doc".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t// ");
//                   sb2.append(anno.getProperties().get("value"));
//                   sb2.append("\n");
//               } else  if("Deprecated".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t// Deprecated\n");
//               } else  if("Null".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t@IsOptional()\n");
//               } else  if("NotNull".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t@IsDefined()\n");
//               } else  if("NotEmpty".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t@IsNotEmpty()\n");
//               } else  if("NotBlank".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t@MinLength(1)\n");
//               } else  if("Positive".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t@IsPositive()\n");
//               } else  if("Negative".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t@IsNegative()\n");
//               } else  if("Email".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t@IsEmail()\n");
//               } else  if("Size".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t@");
//                   Integer min = (Integer)anno.getProperties().get("min");
//                   Integer max = (Integer)anno.getProperties().get("max");
//                   if(min!=null && max!=null){
//                       sb2.append("Length(" + min + ", " + max + ")\n");
//                   } else if(min!=null){
//                       sb2.append("MinLength(" + min + ")\n");
//                   } else if(max!=null){
//                       sb2.append("MaxLength(" + max + ")\n");
//                   }
//               } else  if("Min".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t@Min(" + anno.getProperties().get("value") + ")\n");
//               } else  if("Max".equalsIgnoreCase(anno.getName())){
//                   sb2.append("\t@Max(" + anno.getProperties().get("value") + ")\n");
//               }
//           }
//           return sb2.toString();
//       }
//        return "";
//    }
//
//    private static boolean judgePropertyAnno(List<Anno> annos){
//       if(annos!=null && annos.size()>0){
//           return !annos.stream().noneMatch(a->"NotNull".equalsIgnoreCase(a.getName()) || "NotEmpty".equalsIgnoreCase(a.getName())
//                   || "NotBlank".equalsIgnoreCase(a.getName()));
//       }
//        return false;
//    }
//
//    private static String convertPropertyType(Dto type){
//        if (type==null || type.getName().trim().length()==0){
//            return null;
//        }
//        String name = type.getName().trim();
//        if ("String".equalsIgnoreCase(name)){
//            return "string";
//        } else if("Integer".equalsIgnoreCase(name)){
//            return "number";
//        } else if("Long".equalsIgnoreCase(name)){
//            return "number";
//        } else if("Float".equalsIgnoreCase(name)){
//            return "number";
//        } else if("Double".equalsIgnoreCase(name)){
//            return "number";
//        } else if("Boolean".equalsIgnoreCase(name)){
//            return "boolean";
//        } else if("List".equalsIgnoreCase(name)){
//            return "T[]";
//        } else if("byte[]".equalsIgnoreCase(name)){
//            return "Uint8Array";
//        } else if("Object".equalsIgnoreCase(name)){
//            return "T";
//        }
//
//        if (name.contains("String")){
//            name = name.replaceAll("String", "string");
//        }
//        if (name.contains("Integer")){
//            name = name.replaceAll("Integer", "number");
//        }
//        if (name.contains("Long")){
//            name = name.replaceAll("Long", "number");
//        }
//        if (name.contains("Float")){
//            name = name.replaceAll("Float", "number");
//        }
//        if (name.contains("Double")){
//            name = name.replaceAll("Double", "number");
//        }
//        if (name.contains("Boolean")){
//            name = name.replaceAll("Boolean", "boolean");
//        }
//        if (name.startsWith("List<")){
//            name = name.substring(5, name.length()-1) + "[]";
//        }
//
//        return name;
//    }
//
//    private static void printService(Map<String, String> fileMap, String appName, Map<String, List<Property>> dtoMap, Api api){
//
//        if(api.getName().startsWith("-")){
//            System.out.println("Skip Not Web Service  : " + api.getName());
//            return;
//        }
//
//        String serviceName = api.getName().contains("/")? api.getName().substring(api.getName().lastIndexOf("/")+1) : api.getName();
//        if(!serviceName.endsWith("Service")){
//            serviceName += "Service";
//        }
//        Set<String> dtoSet = new HashSet<>();
//        final StringBuilder serviceSb = new StringBuilder();
//        serviceSb.append(importLine);
//        serviceSb.append("\nexport class " + serviceName + "{\n");
//        serviceSb.append("\treadonly pre = \"" + serviceName.replaceAll("Service$", "") + "/\";\n");
//        serviceSb.append("\n\tconstructor(readonly rpcService: RpcService) {}\n");
//        api.getMethods().stream().filter(m->m.getArg()==null
//                || !"byte[]".equalsIgnoreCase(m.getArg().getName())).forEach(m->{
//            serviceSb.append("\n\t" + m.getName() + "(");
//            String arg = convertPropertyType(m.getArg());
//            if(arg != null){
//                dtoSet.add(arg.contains("<")? arg.substring(0, arg.indexOf("<"))+"<T>" : arg);
//                serviceSb.append("req:" + arg + ",");
//            }
//            serviceSb.append("header?: Headers):Promise<RpcResult<");
//            String res = convertPropertyType(m.getRes());
//            dtoSet.add(res.contains("<")? res.substring(0, res.indexOf("<"))+"<T>" : res);
//            serviceSb.append(res);
//            serviceSb.append(">>{\n");
//            serviceSb.append("\t\treturn this.rpcService.async(this.pre+\"" + m.getName());
//            serviceSb.append("\"," + (arg==null?"null":"req") + ",header);\n");
//            serviceSb.append("\t}\n");
//        });
//        serviceSb.append("\n}\n\n");
//        dtoSet.removeIf(d->d==null || !dtoMap.containsKey(d));
//        if(dtoSet.size()>0){
//            serviceSb.insert(importLine.length(), "import {"
//                    + dtoSet.stream().map(d->d.replaceAll("<T>", "")).collect(Collectors.joining(","))
//                    + "} from './" + appName + "';\n");
//        }
//        fileMap.put(serviceName, serviceSb.toString());
//    }
//
//
//
//}
