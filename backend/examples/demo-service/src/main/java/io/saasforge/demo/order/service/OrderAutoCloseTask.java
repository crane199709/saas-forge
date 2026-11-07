package io.saasforge.demo.order.service;

import io.saasforge.sdk.referencedata.ReferenceData;
import io.saasforge.sdk.scheduling.TenantTask;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class OrderAutoCloseTask {
    private final OrderService orders;
    private final ReferenceData referenceData;
    private final Clock clock;

    public OrderAutoCloseTask(OrderService orders, ReferenceData referenceData, ObjectProvider<Clock> clocks) {
        this.orders = orders;
        this.referenceData = referenceData;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @TenantTask(name = "order.auto-close", cron = "0 */5 * * * *")
    public void closeExpiredOrders() {
        var timeout = referenceData.durationParameter("order.auto-close");
        orders.closeExpired(OffsetDateTime.now(clock).minus(timeout));
    }
}
