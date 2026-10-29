package io.saasforge.iam.subapp.service;

import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.SubAppChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 订阅 platform 广播的 App Registry 变更并更新副本。Shell App 每次读取 {@code /auth/access} 时按副本计算可加载的 Sub-App，
 * 因此入口地址修改后下一次刷新即加载新版本，不需要递增成员版本。
 */
@Service
class SubAppChangedHandler implements EventHandler<SubAppChanged> {
    private static final Logger log = LoggerFactory.getLogger(SubAppChangedHandler.class);

    private final SubAppService apps;

    SubAppChangedHandler(SubAppService apps) {
        this.apps = apps;
    }

    @Override
    public String topic() {
        return SubAppChanged.TOPIC;
    }

    @Override
    public Class<SubAppChanged> eventType() {
        return SubAppChanged.class;
    }

    @Override
    public void handle(SubAppChanged event) {
        if (!apps.apply(event)) {
            log.info("Skipped stale change of Sub-App {} (revision {})", event.code(), event.revision());
        }
    }
}
