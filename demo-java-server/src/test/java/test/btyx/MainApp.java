package test.btyx;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class MainApp implements QuarkusApplication {

    public static void main(String[] args) {
        Quarkus.run(MainApp.class);
    }

    @Override
    public int run(String... args) throws Exception {
        System.out.println("Hello");
        return 0;
    }
}