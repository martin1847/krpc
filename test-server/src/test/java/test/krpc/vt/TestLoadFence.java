/**
 * krpc.tech
 * Copyright (c) 2021-2024 All Rights Reserved.
 */
package test.krpc.vt;

/**
 *
 * @author martin
 * @version 2024/08/31 12:07
 */
public class TestLoadFence {


    public static void main(String[] args) throws InterruptedException {
        ChangeThread changeThread = new ChangeThread();
        new Thread(changeThread).start();

        var unsafe = UnsafeUtil.getUnsafe();
        while (true) {
            boolean flag = changeThread.isFlag();
            // 不加的话一直访问线程工作内存，没有间隙同步主存 (  or Thread.sleep(0) )
            //unsafe.loadFence(); //加入读内存屏障
            if (flag){
                System.out.println("detected flag changed");
                break;
            }
        }
        System.out.println("main thread end");
    }


    static class ChangeThread implements Runnable{

        /**volatile**/ boolean flag=false;
        @Override
        public void run() {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("subThread change flag to:" + flag);
            flag = true;
        }

        public boolean isFlag() {
            return flag;
        }
    }
}
