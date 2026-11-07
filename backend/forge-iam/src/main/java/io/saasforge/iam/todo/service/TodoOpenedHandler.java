package io.saasforge.iam.todo.service;

import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.notification.TodoOpened;
import org.springframework.stereotype.Service;

/** 与 SDK 消费事务共用连接，状态和处理人一并提交。 */
@Service
class TodoOpenedHandler implements EventHandler<TodoOpened> {
    private final TodoService todos;

    TodoOpenedHandler(TodoService todos) {
        this.todos = todos;
    }

    @Override
    public String topic() {
        return TodoOpened.TOPIC;
    }

    @Override
    public Class<TodoOpened> eventType() {
        return TodoOpened.class;
    }

    @Override
    public void handle(TodoOpened event) {
        todos.open(event);
    }
}
