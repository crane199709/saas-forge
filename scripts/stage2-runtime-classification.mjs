// 只接受可还原到特定请求或定时恢复调用栈的 Redis 故障；时间相邻本身不足以豁免。
export function classifyRuntimeError(service, block, security) {
  const line = block.split('\n')[0];
  const at = Date.parse(line.split(' ')[0]);
  const inFault = at >= Date.parse(security.redisStopped?.startedAt)
    && at <= Date.parse(security.redisRestored?.finishedAt);
  if (!inFault) return undefined;
  const requests = security.requests.filter(request => {
    const responseAt = request.at ?? Date.parse(request.finishedAt);
    return request.phase === 'redis-failure' && request.status === 503
      && responseAt >= Date.parse(security.redisStopped?.startedAt)
      && responseAt <= Date.parse(security.redisRestored?.finishedAt);
  });
  const request = requests.find(request => /^[0-9a-f]{32}$/.test(request.traceId)
    && line.includes(request.traceId) && line.includes(request.code)
    && ['TOKEN_REVOCATION_STATUS_UNAVAILABLE', 'SESSION_SECURITY_UNAVAILABLE'].includes(request.code));
  if (request && (request.code === 'TOKEN_REVOCATION_STATUS_UNAVAILABLE'
    || (service === 'iam-service' && line.includes('ConsoleAuthenticationExceptionHandler')
      && (line.includes('io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException')
        || (request.method === 'POST' && request.path === '/api/v2/auth/refresh'
          && line.includes('io.saas.forge.iam.application.authentication.RefreshRotationUnavailableException')))))) {
    return { scenario: 'redis-failure', requestTrace: request.traceId };
  }
  if (service === 'iam-service' && line.includes('TaskUtils$LoggingErrorHandler')
    && line.endsWith('Unexpected error occurred in scheduled task')
    && block.includes('io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException:')
    && block.includes('io.saas.forge.iam.application.authentication.RevocationIndexRecovery.recoverIfNeeded(')
    && (block.includes('io.lettuce.core.RedisCommandTimeoutException:')
      || block.includes('org.springframework.data.redis.RedisConnectionFailureException:'))
    && requests.some(request => request.code === 'TOKEN_REVOCATION_STATUS_UNAVAILABLE')) {
    return { scenario: 'redis-recovery-scheduler-during-injection' };
  }
  return undefined;
}

export function errorBlocks(logs) {
  const blocks = [];
  let current;
  for (const line of logs.split('\n')) {
    if (/\bERROR\b/.test(line)) {
      if (current) blocks.push(current);
      current = line;
    } else if (/^\S+ \d{4}-\d\d-\d\dT\S+\s+(?:TRACE|DEBUG|INFO|WARN)\b/.test(line)) {
      if (current) blocks.push(current);
      current = undefined;
    } else if (current) current += `\n${line}`;
  }
  if (current) blocks.push(current);
  return blocks;
}
