#!/bin/sh
# 创建各服务使用的 Topic（普通消息），重复执行只会覆盖为相同配置。新增 Topic 时加到 TOPICS 中。
# mqadmin 失败时退出码仍为 0，因此以 topicRoute 能查到 broker 作为成功依据；broker 刚启动尚未注册时重试。
set -u

NAMESRV="${NAMESRV:-rocketmq-namesrv:9876}"
TOPICS="demo-order forge-tenant forge-app"
MQADMIN="$ROCKETMQ_HOME/bin/mqadmin"

for topic in $TOPICS; do
  attempt=0
  until "$MQADMIN" topicRoute -n "$NAMESRV" -t "$topic" 2>/dev/null | grep -q brokerName; do
    attempt=$((attempt + 1))
    if [ "$attempt" -gt 30 ]; then
      echo "failed to create topic $topic" >&2
      exit 1
    fi
    "$MQADMIN" updateTopic -n "$NAMESRV" -c DefaultCluster -t "$topic" -a +message.type=NORMAL >/dev/null 2>&1
    sleep 2
  done
  echo "topic ready: $topic"
done
