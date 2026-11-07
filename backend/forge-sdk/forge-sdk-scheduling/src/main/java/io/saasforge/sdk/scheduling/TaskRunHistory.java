package io.saasforge.sdk.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.json.JsonMapper;

/** 查询本服务最近的 Task Run；Redis 读写失败只记录 WARN，查询返回空结果。 */
public final class TaskRunHistory {
    private static final Logger LOG = LoggerFactory.getLogger(TaskRunHistory.class);
    private static final Duration RETENTION = Duration.ofDays(90);
    // 多条命令放在同一脚本中，查询者不能看见超过上限的中间列表。
    private static final String ARCHIVE = """
            local function archive(value, problem)
                redis.call('lpush', KEYS[2], value)
                redis.call('ltrim', KEYS[2], 0, 99)
                if problem then
                    redis.call('lpush', KEYS[3], value)
                    redis.call('ltrim', KEYS[3], 0, 99)
                end
            end
            local function refresh(ttl)
                for i = 1, 3 do redis.call('expire', KEYS[i], ttl) end
            end
            """;
    private static final DefaultRedisScript<String> CURRENT = new DefaultRedisScript<>("""
            local current = redis.call('get', KEYS[1])
            if not current then return nil end
            if cjson.decode(current).token == redis.call('get', KEYS[2]) then return '1' .. current end
            return '0' .. current
            """, String.class);
    private static final DefaultRedisScript<Long> START = new DefaultRedisScript<>(ARCHIVE + """
            if redis.call('get', KEYS[4]) ~= ARGV[1] then return 0 end
            local current = redis.call('get', KEYS[1])
            if current and current ~= ARGV[3] then return 0 end
            if current and ARGV[4] ~= '' then archive(ARGV[4], true) end
            redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[5])
            refresh(ARGV[5])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>(ARCHIVE + """
            local current = redis.call('get', KEYS[1])
            local ownsLock = redis.call('get', KEYS[4]) == ARGV[1]
            if current then
                if cjson.decode(current).token ~= ARGV[1] then return 0 end
            elseif not ownsLock then return 0 end
            if ownsLock then archive(ARGV[2], ARGV[3] == 'true')
            else archive(ARGV[5], true) end
            if current then redis.call('del', KEYS[1]) end
            refresh(ARGV[4])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final String service;
    private final JsonMapper json = JsonMapper.builder().build();

    TaskRunHistory(StringRedisTemplate redis, String service) {
        this.redis = redis;
        this.service = service;
    }

    /**
     * 查询当前运行；不存在或 Redis 不可用时为空。
     *
     * @param name 服务内唯一任务名
     * @return 当前运行
     */
    public Optional<TaskRun> current(String name) {
        try {
            // 同一原子快照读取 current 与锁，避免新持有者切换时误判。
            String value = redis.execute(CURRENT, List.of(key(name, "current"), lockKey(name)));
            if (value == null) return Optional.empty();
            TaskRun run = json.readValue(value.substring(1), Current.class).run();
            return Optional.of(value.charAt(0) == '1' ? run : interrupted(run));
        } catch (RuntimeException error) {
            LOG.warn("Task {} run history could not be read", name, error);
            return Optional.empty();
        }
    }

    /**
     * 查询最近结束的运行，按结束顺序倒序排列。
     *
     * @param name 服务内唯一任务名
     * @return 最近结束的运行；Redis 不可用时为空
     */
    public List<TaskRun> recent(String name) {
        return list(name, "recent");
    }

    /**
     * 查询最近非成功的运行，按结束顺序倒序排列。
     *
     * @param name 服务内唯一任务名
     * @return 最近非成功运行；Redis 不可用时为空
     */
    public List<TaskRun> problems(String name) {
        return list(name, "problems");
    }

    TaskRun start(String name, TaskRun.Type type, String token) {
        var run = new TaskRun(
                UUID.randomUUID(),
                name,
                type,
                TaskRun.Trigger.SCHEDULED,
                null,
                null,
                TaskRun.Status.RUNNING,
                Instant.now(),
                null,
                0,
                0,
                List.of(),
                null);
        try {
            String previous = redis.opsForValue().get(key(name, "current"));
            String abandoned = previous == null
                    ? ""
                    : json.writeValueAsString(
                            interrupted(json.readValue(previous, Current.class).run()));
            // 旧运行可能恰好完成；比较读取到的原值后再归档，避免覆盖接替者。
            redis.execute(
                    START,
                    keys(name),
                    token,
                    json.writeValueAsString(new Current(token, run)),
                    previous == null ? "" : previous,
                    abandoned,
                    Long.toString(RETENTION.toSeconds()));
        } catch (RuntimeException error) {
            LOG.warn("Task {} run history could not be started", name, error);
        }
        return run;
    }

    void finish(TaskRun run, String token) {
        try {
            redis.execute(
                    FINISH,
                    keys(run.name()),
                    token,
                    json.writeValueAsString(run),
                    Boolean.toString(run.status() != TaskRun.Status.SUCCEEDED),
                    Long.toString(RETENTION.toSeconds()),
                    json.writeValueAsString(interrupted(run)));
        } catch (RuntimeException error) {
            LOG.warn("Task {} run history could not be finished", run.name(), error);
        }
    }

    private List<TaskRun> list(String name, String suffix) {
        try {
            List<String> values = redis.opsForList().range(key(name, suffix), 0, 99);
            return values == null
                    ? List.of()
                    : values.stream()
                            .map(value -> json.readValue(value, TaskRun.class))
                            .toList();
        } catch (RuntimeException error) {
            LOG.warn("Task {} run history could not be read", name, error);
            return List.of();
        }
    }

    private static TaskRun interrupted(TaskRun run) {
        return new TaskRun(
                run.id(),
                run.name(),
                run.type(),
                run.trigger(),
                run.operatorId(),
                run.targetTenantId(),
                TaskRun.Status.INTERRUPTED,
                run.startedAt(),
                Instant.now(),
                run.successCount(),
                run.failureCount(),
                run.failedTenants(),
                run.errorSummary());
    }

    private List<String> keys(String name) {
        return List.of(key(name, "current"), key(name, "recent"), key(name, "problems"), lockKey(name));
    }

    private String lockKey(String name) {
        return "forge:task:" + service + ":" + name;
    }

    private String key(String name, String suffix) {
        return "forge:task-run:" + service + ":" + name + ":" + suffix;
    }

    private record Current(String token, TaskRun run) {
        @Override
        public String toString() {
            return "Current[run=" + run + "]";
        }
    }
}
