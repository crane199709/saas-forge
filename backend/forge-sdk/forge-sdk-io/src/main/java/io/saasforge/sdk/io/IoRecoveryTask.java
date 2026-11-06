package io.saasforge.sdk.io;

import java.util.UUID;

/** Global Task 收尾所需的元数据，避免将任务上下文或凭证保存到数据库。 */
record IoRecoveryTask(UUID id, String type, UUID sourceObjectId, UUID actorId, long rowCount, String summary) {}
