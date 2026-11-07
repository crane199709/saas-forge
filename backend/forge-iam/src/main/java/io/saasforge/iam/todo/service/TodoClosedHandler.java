package io.saasforge.iam.todo.service;

import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.notification.TodoClosed;
import org.springframework.stereotype.Service;

/** 与 SDK 消费事务共用连接，关闭状态只接受业务发生时间更晚的事件。 */
@Service
class TodoClosedHandler implements EventHandler<TodoClosed> {
    private final TodoService todos;

    TodoClosedHandler(TodoService todos) {
        this.todos = todos;
    }

    @Override
    public String topic() {
        return TodoClosed.TOPIC;
    }

    @Override
    public Class<TodoClosed> eventType() {
        return TodoClosed.class;
    }

    @Override
    public void handle(TodoClosed event) {
        todos.close(event);
    }
}
