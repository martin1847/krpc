package tech.krpc.common;

import lombok.Data;

import java.lang.reflect.Type;

/**
 * 2020-04-03 16:03
 *
 * @author Martin.C
 */
@Data
public abstract class AbstractContext<Res extends ResultWrapper, Argument, Self extends  RpcContext<Res>> implements  RpcContext<Res> {

    protected Class service;

    protected String method;

    protected Type resDto;
    
    protected Argument arg;


    protected FilterChain<Res,Self> underlyFunc;

    public AbstractContext(){}
    public AbstractContext(Class service, String method, Type resDto, Argument arg, FilterChain<Res,Self> lastChain) {
        this.service = service;
        this.method = method;
        this.resDto = resDto;
        this.arg = arg;
        this.underlyFunc = lastChain;
    }

    @Override
    public Res underlyCall() throws  Throwable{
        return underlyFunc.invoke((Self)this);
    }
}
