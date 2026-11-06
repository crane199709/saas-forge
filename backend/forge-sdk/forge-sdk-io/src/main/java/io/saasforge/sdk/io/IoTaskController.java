package io.saasforge.sdk.io;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.storage.DownloadTicket;
import io.saasforge.sdk.web.PageQuery;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 每个引入 IO 的业务服务独立暴露本人任务接口，不提供跨服务任务中心。 */
@RestController
@RequestMapping("/io/tasks")
public final class IoTaskController {
    private final IoTasks tasks;

    IoTaskController(IoTasks tasks) {
        this.tasks = tasks;
    }
    /** 分页列出当前 Membership 的任务。 */
    @GetMapping
    public ApiResponse<List<IoTask>> list(PageQuery page) {
        return ApiResponse.page(tasks.list(page.size(), page.offset()), page.toPageMeta(tasks.count()));
    }
    /** 查询本人任务状态。 */
    @GetMapping("/{id}")
    public ApiResponse<IoTask> get(@PathVariable UUID id) {
        return ApiResponse.ok(tasks.get(id));
    }
    /** 申请本人结果文件的下载票据。 */
    @GetMapping("/{id}/download")
    public ApiResponse<DownloadTicket> download(@PathVariable UUID id) {
        return ApiResponse.ok(tasks.download(id));
    }
}
