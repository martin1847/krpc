package test.btyx;

import com.bt.convert.UserConvert;
import com.bt.demo.dto.User;

public class MainApp {

    public static void main(String[] args) {

        var user = new User();
        user.setId(11);
        user.setName("who am i");

        //System.out.println(
        //        UserConvert.INSTANCE.toQuery(user)
        //);



    }
}