/**
 * Alipay.com Inc.
 * Copyright (c) 2004-2021 All Rights Reserved.
 */
package bt;

/**
 *
 * @author yyc
 * @version : TestThread.java, v 0.1 2021年08月30日 2:14 PM yyc Exp $
 */
public class TestThread {


    static int i;

    // TODO state Machine
    static volatile boolean flag;

    static class PrintRun implements Runnable{

        final boolean status;

        PrintRun(boolean status) {this.status = status;}

        @Override
        public void run() {
            while (i<100){
                if(flag == status){
                    System.out.print(Thread.currentThread().getName() +" \t ");
                    System.out.println(i++);
                    flag = !flag;
                    // usage of sleep(0) which is more or less the C equivalent of yield.
                    //Thread.yield();
                    //try {
                    //    Thread.sleep(0);
                    //} catch (InterruptedException e) {
                    //    e.printStackTrace();
                    //}
                }
            }
        }
    }

    public static void main(String[] args) throws  InterruptedException {


        var t1 = new Thread(new PrintRun(true));
        var t2 = new Thread(new PrintRun(false));

        t1.start();
        t2.start();


        t1.join();
        t2.join();
    }
}