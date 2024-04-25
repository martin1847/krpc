//package tech.krpc.context;
//
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//
//public class DiContextSimpleImpl implements DiContext {
//
//    Map<String, Object> chm = new ConcurrentHashMap<>();
//
//    @Override
//    public boolean containsBean(String name) {
//        return chm.containsKey(name);
//    }
//
//    @Override
//    public Object getBean(String beanName) {
//        return chm.get(beanName);
//    }
//
//    @Override
//    public <T> T registerBean(String beanName, Object object) {
//        chm.put(beanName, object);
//        return (T)object;
//    }
//}
