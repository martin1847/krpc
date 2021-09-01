package com.bt.rpc.context;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
@Slf4j
public class ContextUtil {


    /**
     * get all the field names from the object o
     */
    public static String[] getFieldNames(Object o){
        Field[] fields = o.getClass().getDeclaredFields();
        String[] fieldNames = new  String[fields.length];
        for (int i = 0; i < fields.length ; i++) {
            fieldNames[i] = fields[i].getName();
        }
        return fieldNames;
    }

    /**
     * get all the field values from the object o
     */
    public static Object[] getFieldValues(Object o) throws InvocationTargetException, IllegalAccessException {
        String[] fieldNames = ContextUtil.getFieldNames(o);
        Object[] fieldValues = new Object[fieldNames.length];
        for (int i = 0; i < fieldNames.length; i++) {

            String firstletter = fieldNames[i].substring(0, 1).toUpperCase();
            String getMethodName = "get" + firstletter + fieldNames[i].substring(1);
            Method method = null;
            Object fieldValue;
            try {
                method = o.getClass().getMethod(getMethodName);
            } catch (NoSuchMethodException e) {
                log.error("NoSuchMethodException: ",e);
            }


            fieldValue = method.invoke(o);

            fieldValues[i] = fieldValue;
        }

        return fieldValues;
    }



}
