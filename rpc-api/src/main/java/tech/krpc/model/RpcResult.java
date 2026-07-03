package tech.krpc.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * 2020-01-02 17:21
 *
 * @author Martin.C
 */
@Data
public class RpcResult<DTO> implements Serializable {

    public static final int OK = 0;
    public static final int DATA_LOSS = 15;

    /**
     * google.rpc.Code/CommonCode 的超集<br>
     * 自定义业务异常码，以百为业务区间，大业务以千为区间<br>
     * 除非必要，禁止使用java Exception传递错误信息，请定义异常码<br>
     * 最小0（msg/data逻辑互斥），不支持负值
     */
    int code = 0 ;

    /**
     * code > 0 的时候有值，不为null
     */
    String msg;

    /**
     * code == 0 的时候有值，不为null
     */
    DTO data;


    @JsonIgnore
    public boolean isOk(){
        return OK == code;
    }

    // C3 + AUD-omp-28: reinterpret THIS failure result as another payload type. Only valid on a
    // failure (code>0). Calling it on an OK result was a silent contract break — the "error"
    // carried no error. Guard it: an OK result has no error to forward.
    @JsonIgnore
    public <T> RpcResult<T> error() {
        if (isOk()) {
            throw new IllegalStateException("error() called on an OK result (code==0); nothing to forward");
        }
        return (RpcResult<T>) this;
    }


    // C3 + AUD-omp-28: on the OK branch, build a NEW RpcResult instead of mutating + returning
    // `this`. The old code did `((RpcResult<T>)this).data = res; return this` — it re-typed the
    // SAME object and swapped its data field, so any caller still holding the original reference
    // silently saw its DTO replaced (aliasing). A mapping op must not mutate its receiver.
    public <T> RpcResult<T> ifOk(Function<DTO,T> dataHandler) {
        if( OK == code ){
            var res = dataHandler.apply(data);
            if(null != res) {
                return RpcResult.ok(res);
            }else {
                return RpcResult.error(DATA_LOSS, "call ifOk But got null !!!");
            }
        }
        return (RpcResult<T>) this;
    }


    public DTO orElseThrow() {
        if (data == null) {
            throw new IllegalStateException("error code: "+code +" ,caused by: "+msg);
        }
        return data;
    }


    // C3 + AUD-omp-28: `assert` is a no-op under production `-da`, so the ok(nonNull)/error(code>0)
    // envelope invariants (RpcResult.java:22-24,28-36) never actually held at runtime. Enforce them
    // unconditionally: ok(null) and error(code<=0)/error(null msg) are programming errors.
    public static <T> RpcResult<T> ok(T data){
        Objects.requireNonNull(data, "RpcResult.ok(data): data must be non-null (code==0 ⇒ data present)");
        RpcResult<T> res = new RpcResult<>();
        res.data = data;
        return res;
    }

    public static <T> RpcResult<T> error(int code, String msg) {
        Objects.requireNonNull(msg, "RpcResult.error(code,msg): msg must be non-null");
        if (code <= 0) {
            throw new IllegalArgumentException("RpcResult.error(code,msg): code must be > 0 (0 is OK, negatives unsupported), got " + code);
        }
        RpcResult<T> res = new RpcResult<>();
        res.code = code;
        res.msg = msg;
        return res;
    }

    public static <T> RpcResult<T> error(CommonCode it) {
        RpcResult<T> res = new RpcResult<>();
        res.code = it.value;
        res.msg = it.name();
        return res;
    }

    public static <T> RpcResult<T> error(RpcResult<?> error) {
        return (RpcResult<T>) error;
    }

}
