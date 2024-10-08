/**
 * Martin.Cong
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package test.krpc;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import tech.krpc.model.PagedQuery;
import tech.test.krpc.dto.TimeReq;

/**
 *
 * @author Martin.C
 * @version 2023/01/10 10:32
 */
public class TestValidator {

    public static void main(String[] args) {
        ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();

        Validator validator = validatorFactory.getValidator();
        var req = new TimeReq();
        PagedQuery<TimeReq> obj = new PagedQuery<>(1,10,req);

        var violationSet  = validator.validate(obj);
        if (violationSet.size() > 0) {
            for (var v : violationSet) {
                System.out.println(v.getPropertyPath() +" -> " + v.getMessage());
            }
        }

        validatorFactory.close();
    }
}