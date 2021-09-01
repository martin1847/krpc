package com.bt.rpc.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 2020-04-03 15:42
 *
 * @author Martin.C
 */
public class FilterInvokeHelper<Res extends ResultWrapper, Ctx extends RpcContext<Res>> {


    final RpcFilter<Res, Ctx>[] Filters;

//    public final FilterChain<Res, Ctx> filterChain;

    public FilterInvokeHelper(RpcFilter<Res, Ctx>[] filters) {
        this.Filters = filters;
//        this.filterChain = BuildFilterChain(filters.length);
    }

    public <F extends RpcFilter<Res, Ctx>>  FilterInvokeHelper(List<F> global,List<F> filterList) {

        var flist  =  global;

        if(null != filterList && filterList.size() > 0){
            var list = new ArrayList<F>(flist.size() + filterList.size());
            list.addAll(flist);
            list.addAll(filterList);
            flist = list;
        }
        this.Filters = flist.toArray(new RpcFilter[0]);
//        this.filterChain = BuildFilterChain(filters.length);
    }




    public FilterChain<Res, Ctx> buildFilterChain() {
        int len = Filters.length;
        switch (len) {
            case 0: // reduce a wait
                //return  req -> new ValueTask<ResponseContext>(req.Invoker(req));
                return Ctx::underlyCall;
            case 1:
                return this::InvokeWithFilter1;
            case 2:
                return this::InvokeWithFilter2;
            case 3:
                return this::InvokeWithFilter3;
            case 4:
                return this::InvokeWithFilter4;
            case 5:
                return this::InvokeWithFilter5;
            case 6:
                return this::InvokeWithFilter6;
            case 7:
                return this::InvokeWithFilter7;
            default:
                return ctx -> InvokeRecursive(-1, ctx);
        }
    }

    private Res InvokeWithFilter1(Ctx context) throws  Throwable{
        return Filters[0].Invoke(context, Ctx::underlyCall);
    }

    Res InvokeWithFilter2(Ctx context) throws Throwable{
        return Filters[0].Invoke(context,
                x1 -> Filters[1].Invoke(x1, Ctx::underlyCall));
    }

    Res InvokeWithFilter3(Ctx context) throws Throwable{
        return Filters[0].Invoke(context,
                x1 -> Filters[1].Invoke(x1,
                        x2 -> Filters[2].Invoke(x2, Ctx::underlyCall)));
    }

    Res InvokeWithFilter4(Ctx context) throws Throwable {
        return Filters[0].Invoke(context,
                x1 -> Filters[1].Invoke(x1,
                        x2 -> Filters[2].Invoke(x2,
                                x3 -> Filters[3].Invoke(x3, Ctx::underlyCall))));
    }

    Res InvokeWithFilter5(Ctx context) throws Throwable{
        return Filters[0].Invoke(context,
                x1 -> Filters[1].Invoke(x1,
                        x2 -> Filters[2].Invoke(x2,
                                x3 -> Filters[3].Invoke(x3,
                                        x4 -> Filters[4].Invoke(x4, Ctx::underlyCall)))));
    }

    Res InvokeWithFilter6(Ctx context) throws Throwable{
        return Filters[0].Invoke(context,
                x1 -> Filters[1].Invoke(x1,
                        x2 -> Filters[2].Invoke(x2,
                                x3 -> Filters[3].Invoke(x3,
                                        x4 -> Filters[4].Invoke(x4,
                                                x5 -> Filters[5].Invoke(x5, Ctx::underlyCall))))));
    }

    Res InvokeWithFilter7(Ctx context) throws Throwable{
        return Filters[0].Invoke(context,
                x1 -> Filters[1].Invoke(x1,
                        x2 -> Filters[2].Invoke(x2,
                                x3 -> Filters[3].Invoke(x3,
                                        x4 -> Filters[4].Invoke(x4,
                                                x5 -> Filters[5].Invoke(x5,
                                                        x6 -> Filters[6].Invoke(x6, Ctx::underlyCall)))))));
    }

    private Res InvokeRecursive(int pre, Ctx context) throws Throwable {
        var cur = pre + 1;// start from -1
        if (cur != Filters.length) {
            return Filters[cur].Invoke(context, ctx -> InvokeRecursive(cur, ctx));
        } else {
            return context.underlyCall();
        }
    }
}
