#set( $dollar = '$' )
#set( $hash = '#' )
package ${package}.order.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum OrderErrorCode implements ErrorCode {
    /** 其他租户的订单与不存在的订单同样返回本错误，不泄露它是否存在。 */
    NOT_FOUND("order.notFound", 404);

    private final String code;
    private final int httpStatus;

    OrderErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
