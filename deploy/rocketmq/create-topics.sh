#!/bin/sh
# 创建各服务使用的 Topic（普通消息），重复执行只会覆盖为相同配置。新增 Topic 时加到 TOPICS 中。
# mqadmin 失败时退出码仍为 0，因此以 topicRoute 能查到 broker 作为成功依据；broker 刚启动尚未注册时重试。
set -u

NAMESRV="${NAMESRV:-rocketmq-namesrv:9876}"
TOPICS="demo-order forge-tenant forge-app forge-audit"
MQADMIN="$ROCKETMQ_HOME/bin/mqadmin"

# SDK 在达到此投递次数时先可靠保存 Dead Event，消费组不能更早转入 RocketMQ 死信主题。
# 5.3.2 Proxy 下发的总投递上限为 retryMaxTimes + 1；这里保守要求重试次数本身也不低于 SDK 上限。
CONSUMER_GROUPS="${CONSUMER_GROUPS:-forge-iam forge-platform demo-service}"
MAX_DELIVERY_ATTEMPTS="${FORGE_MQ_MAX_DELIVERY_ATTEMPTS:-16}"
RETRY_MAX_TIMES="${RETRY_MAX_TIMES:-16}"
for value in "$MAX_DELIVERY_ATTEMPTS" "$RETRY_MAX_TIMES"; do
  case "$value" in
    ''|*[!0-9]*) echo "delivery attempts and retry count must be positive integers" >&2; exit 1 ;;
  esac
done
if [ "$MAX_DELIVERY_ATTEMPTS" -lt 1 ] || [ "$RETRY_MAX_TIMES" -lt "$MAX_DELIVERY_ATTEMPTS" ]; then
  echo "RocketMQ retry count must be at least forge.mq.max-delivery-attempts ($MAX_DELIVERY_ATTEMPTS)" >&2
  exit 1
fi

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

for group in $CONSUMER_GROUPS; do
  "$MQADMIN" updateSubGroup -n "$NAMESRV" -c DefaultCluster -g "$group" -r "$RETRY_MAX_TIMES" >/dev/null 2>&1
  # mqadmin 失败时仍可能返回 0，必须回读实际配置。每个 broker 的值都要满足下限。
  actual=$("$MQADMIN" getConsumerConfig -n "$NAMESRV" -g "$group" 2>/dev/null)
  retries=$(printf '%s\n' "$actual" | sed -n 's/^retryMaxTimes[[:space:]]*=[[:space:]]*\([0-9][0-9]*\)[[:space:]]*$/\1/p')
  if [ -z "$retries" ]; then
    echo "failed to read retry count for consumer group $group" >&2
    exit 1
  fi
  for retry in $retries; do
    if [ "$retry" -lt "$MAX_DELIVERY_ATTEMPTS" ]; then
      echo "consumer group $group retry count $retry is below $MAX_DELIVERY_ATTEMPTS" >&2
      exit 1
    fi
  done
  echo "consumer group ready: $group (retry count $retries)"
done
