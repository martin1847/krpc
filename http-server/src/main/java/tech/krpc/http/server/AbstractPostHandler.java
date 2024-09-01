/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.http.server;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 *
 * @author Martin.C
 * @version 2021/12/22 9:24 AM
 */
public abstract class AbstractPostHandler<ParamDTO> implements PostHandler<ParamDTO> {

    public final Class<ParamDTO> paramClass;

    public final boolean useValidator ;

    public AbstractPostHandler(){
        this(true);
    }
    public AbstractPostHandler(boolean useValidator) {
        Type[] pTypes = getParameterizedTypes(this);
        paramClass = (Class<ParamDTO>) pTypes[0];
        this.useValidator = useValidator;
    }

    static Type[] getParameterizedTypes(Object object) {
        Type superclassType = object.getClass().getGenericSuperclass();

        // 简单上硕源两次
        if(superclassType instanceof  Class){
            superclassType = ((Class<?>) superclassType).getGenericSuperclass();
        }

        if(superclassType instanceof  Class){
            superclassType = ((Class<?>) superclassType).getGenericSuperclass();
        }

        //
        //if (!ParameterizedType.class.isAssignableFrom(superclassType.getClass())) {
        //    return null;
        //}
        return ((ParameterizedType) superclassType).getActualTypeArguments();
    }

    @Override
    public boolean useValidator() {
        return useValidator;
    }

    @Override
    public Class<ParamDTO> getParamClass() {
        return paramClass;
    }
}